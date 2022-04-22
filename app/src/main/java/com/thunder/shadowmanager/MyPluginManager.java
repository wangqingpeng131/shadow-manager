package com.thunder.shadowmanager;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.tencent.shadow.core.manager.installplugin.InstallPluginException;
import com.tencent.shadow.core.manager.installplugin.InstalledPlugin;
import com.tencent.shadow.core.manager.installplugin.InstalledType;
import com.tencent.shadow.core.manager.installplugin.PluginConfig;
import com.tencent.shadow.dynamic.host.EnterCallback;
import com.tencent.shadow.dynamic.host.FailedException;
import com.tencent.shadow.dynamic.manager.PluginManagerThatUseDynamicLoader;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author Afra55
 * @date 2020/5/19
 * A smile is the best business card.
 */
public class MyPluginManager extends PluginManagerThatUseDynamicLoader {

    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private ExecutorService mFixedPool = Executors.newFixedThreadPool(4);
    private String mProcessServiceName = "";

    public MyPluginManager(Context context) {
        super(context);
    }

    /**
     * TODO 下面内容需要自己实现
     *
     * @return PluginManager实现的别名，用于区分不同PluginManager实现的数据存储路径
     */
    @Override
    protected String getName() {
        return "PiaPiaPia";
    }

    /**
     * TODO 下面内容需要自己实现
     *
     * @param context  context
     * @param fromId   标识本次请求的来源位置，用于区分入口
     * @param bundle   参数列表
     * @param callback 用于从PluginManager实现中返回View
     */
    @Override
    public void enter(final Context context, long fromId, Bundle bundle, final EnterCallback callback) {
        // 插件 zip 包地址，可以直接写在这里，也用Bundle可以传进来

        final String partKey = bundle.getString("part_key");
        mProcessServiceName = bundle.getString("process_service_name");
        String appVersion = bundle.getString(" app_version");
        final String className = "com.tencent.shadow.sample.plugin.runtime.PluginDefaultProxyActivity";
        final String pluginZipPath = bundle.getString("p_p");
        if (fromId == 1011) { // 打开 Activity 示例
            final Bundle extras = bundle.getBundle("extra_to_plugin_bundle");
            if (callback != null) {
                // 开始加载插件了，实现加载布局
                callback.onShowLoadingView(null);
            }
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        InstalledPlugin installedPlugin = installPlugin(context, pluginZipPath, null, true);//这个调用是阻塞的
                        loadPlugin(installedPlugin.UUID, partKey);
                        Intent pluginIntent = new Intent();
                        pluginIntent.putExtra("app_version", appVersion);
                        pluginIntent.setClassName(
                                context.getPackageName(),
                                className
                        );
                        if (extras != null) {
                            pluginIntent.replaceExtras(extras);
                        }

                        Intent intent = mPluginLoader.convertActivityIntent(pluginIntent);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mPluginLoader.startActivityInPluginProcess(intent);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    if (callback != null) {
                        mUiHandler.post(() -> {
                            // 到这里插件就启动完成了
                            callback.onCloseLoadingView();
                            callback.onEnterComplete();
                        });
                    }
                }
            });

        }
    }

    public InstalledPlugin installPlugin(Context context, String zip, String hash, boolean odex) throws IOException, JSONException, InterruptedException, ExecutionException {
        final PluginConfig pluginConfig = installPluginFromZip(new File(zip), hash);
        final String uuid = pluginConfig.UUID;
        List<Future> futures = new LinkedList<>();
        List<Future<Pair<String, String>>> extractSoFutures = new LinkedList<>();
        if (pluginConfig.runTime != null && pluginConfig.pluginLoader != null) {
            Future odexRuntime = mFixedPool.submit(new Callable() {
                @Override
                public Object call() throws Exception {
                    oDexPluginLoaderOrRunTime(uuid, InstalledType.TYPE_PLUGIN_RUNTIME,
                            pluginConfig.runTime.file);
                    return null;
                }
            });
            futures.add(odexRuntime);
            Future odexLoader = mFixedPool.submit(new Callable() {
                @Override
                public Object call() throws Exception {
                    oDexPluginLoaderOrRunTime(uuid, InstalledType.TYPE_PLUGIN_LOADER,
                            pluginConfig.pluginLoader.file);
                    return null;
                }
            });
            futures.add(odexLoader);
        }
        for (Map.Entry<String, PluginConfig.PluginFileInfo> plugin : pluginConfig.plugins.entrySet()) {
            final String partKey = plugin.getKey();
            final File apkFile = plugin.getValue().file;
            Future<Pair<String, String>> extractSo = mFixedPool.submit(() -> extractSo(uuid, partKey, apkFile));
            futures.add(extractSo);
            extractSoFutures.add(extractSo);
            if (odex) {
                Future odexPlugin = mFixedPool.submit(new Callable() {
                    @Override
                    public Object call() throws Exception {
                        oDexPlugin(uuid, partKey, apkFile);
                        return null;
                    }
                });
                futures.add(odexPlugin);
            }
        }

        for (Future future : futures) {
            future.get();
        }
        Map<String, String> soDirMap = new HashMap<>();
        for (Future<Pair<String, String>> future : extractSoFutures) {
            Pair<String, String> pair = future.get();
            soDirMap.put(pair.first, pair.second);
        }
        onInstallCompleted(pluginConfig, soDirMap);

        return getInstalledPlugins(1).get(0);
    }

    private void loadPluginLoaderAndRuntime(String uuid) throws RemoteException, TimeoutException, FailedException {
        if (mPpsController == null) {
            bindPluginProcessService(mProcessServiceName);
            waitServiceConnected(10, TimeUnit.SECONDS);
        }
        loadRunTime(uuid);
        loadPluginLoader(uuid);
    }

    protected void loadPlugin(String uuid, String partKey) throws RemoteException, TimeoutException, FailedException {
        loadPluginLoaderAndRuntime(uuid);
        Map map = mPluginLoader.getLoadedPlugin();
        if (!map.containsKey(partKey)) {
            mPluginLoader.loadPlugin(partKey);
        }
        Boolean isCall = (Boolean) map.get(partKey);
        if (isCall == null || !isCall) {
            mPluginLoader.callApplicationOnCreate(partKey);
        }
    }
}

