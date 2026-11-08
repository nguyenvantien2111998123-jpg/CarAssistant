package com.carassistant.v10.hook;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Mirrors the exact warning asset WYN asks its Flutter asset bundle to load.
 * No sensor channels are logged and no GPS/warning logic is duplicated.
 */
public final class WynWarningLinkProbe {
    private static final String PUBLISH_URI = "content://com.carassistant.v10.wynwarning";
    private static final Set<String> INSTALLED =
            Collections.synchronizedSet(new HashSet<String>());
    private static volatile String lastPublishedKey = "";
    private static volatile Context appContext;
    private static final ExecutorService PUBLISHER = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "CarAsst-WYN-asset-bridge");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private WynWarningLinkProbe() { }

    public static void install(ClassLoader loader, Context context) {
        if (context != null) appContext = context.getApplicationContext();
        if (loader == null) return;
        String key = Integer.toHexString(System.identityHashCode(loader));
        if (!INSTALLED.add(key)) return;
        hookFlutterLoader(loader);
        hookAssetManager();
    }

    private static void hookFlutterLoader(ClassLoader loader) {
        try {
            Class<?> type = Class.forName(
                    "io.flutter.embedding.engine.loader.FlutterLoader", false, loader);
            for (Method method : type.getDeclaredMethods()) {
                if (!"getLookupKeyForAsset".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 0 || params[0] != String.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        String input = p.args == null ? "" : String.valueOf(p.args[0]);
                        String output = String.valueOf(p.getResult());
                        String key = extractWarningKey(input);
                        if (key == null) key = extractWarningKey(output);
                        if (key != null) publishWarningAsset(key);
                    }
                });
            }
        } catch (Throwable ignored) { }
    }

    private static void hookAssetManager() {
        hookAssetMethod("open");
        hookAssetMethod("openFd");
    }

    private static void hookAssetMethod(String methodName) {
        try {
            XposedBridge.hookAllMethods(AssetManager.class, methodName, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length == 0
                            || !(p.args[0] instanceof String)) return;
                    String key = extractWarningKey((String) p.args[0]);
                    if (key != null) publishWarningAsset(key);
                }
            });
        } catch (Throwable ignored) { }
    }

    private static String extractWarningKey(String value) {
        if (value == null) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        int sign = lower.indexOf("road_sign_");
        if (sign >= 0) {
            String tail = value.substring(sign + "road_sign_".length());
            int end = tail.toLowerCase(Locale.ROOT).indexOf(".svg");
            if (end > 0) {
                String code = tail.substring(0, end).replaceAll("[^A-Za-z0-9]", "");
                if (code.matches("(?i)(DP|P|R)\\d{2,3}[A-Za-z]?\\d?")) {
                    return "road_sign_" + code;
                }
            }
        }
        if (lower.contains("horizontal-traffic-light.png")) return "traffic_light";
        if (lower.contains("speed_camera.png")) return "speed_camera";
        return null;
    }

    private static synchronized void publishWarningAsset(String key) {
        Context context = appContext;
        if (context == null || key == null || key.equals(lastPublishedKey)) return;
        lastPublishedKey = key;
        PUBLISHER.execute(() -> {
            try {
                Bundle extras = new Bundle();
                extras.putString("asset_key", key);
                Bundle result = context.getContentResolver().call(
                        Uri.parse(PUBLISH_URI), "publish_asset", null, extras);
                if (result == null || !result.getBoolean("accepted", false)) {
                    synchronized (WynWarningLinkProbe.class) {
                        if (key.equals(lastPublishedKey)) lastPublishedKey = "";
                    }
                }
            } catch (Throwable error) {
                synchronized (WynWarningLinkProbe.class) {
                    if (key.equals(lastPublishedKey)) lastPublishedKey = "";
                }
            }
        });
    }
}
