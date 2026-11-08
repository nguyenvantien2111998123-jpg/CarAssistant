package com.carassistant.v10.hook;

import android.content.Context;
import android.content.ContentResolver;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Read-only WYN Flutter/asset trace. Logs channel names and payload lengths only. */
public final class WynWarningLinkProbe {
    private static final String TAG = "[AACast WYN Trace]";
    private static final Set<String> INSTALLED =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> PUBLISHED =
            Collections.synchronizedSet(new HashSet<String>());
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
        hookFlutterJni(loader);
        hookFlutterLoader(loader);
        hookAssetManager();
        XposedBridge.log(TAG + " installed classLoader=" + key);
    }

    private static void hookFlutterJni(ClassLoader loader) {
        try {
            Class<?> type = Class.forName("io.flutter.embedding.engine.FlutterJNI", false, loader);
            hookMessageMethod(type, "handlePlatformMessage", "DART_TO_ANDROID");
            hookMessageMethod(type, "dispatchPlatformMessage", "ANDROID_TO_DART");
            XposedBridge.hookAllMethods(type, "dispatchEmptyPlatformMessage", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args != null && p.args.length > 0)
                        XposedBridge.log(TAG + " direction=ANDROID_TO_DART_EMPTY channel="
                                + p.args[0] + " responseId=" + (p.args.length > 1 ? p.args[1] : "?"));
                }
            });
            XposedBridge.log(TAG + " FlutterJNI hooks ready");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " FlutterJNI unavailable=" + e.getClass().getSimpleName());
        }
    }

    private static void hookMessageMethod(Class<?> type, String name, String direction) {
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!name.equals(method.getName())) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        String channel = p.args != null && p.args.length > 0
                                ? String.valueOf(p.args[0]) : "?";
                        ByteBuffer buffer = null;
                        for (Object arg : p.args)
                            if (arg instanceof ByteBuffer) { buffer = (ByteBuffer) arg; break; }
                        XposedBridge.log(TAG + " direction=" + direction + " channel=" + channel
                                + " bytesRemaining=" + (buffer == null ? -1 : buffer.remaining())
                                + " position=" + (buffer == null ? -1 : buffer.position())
                                + " limit=" + (buffer == null ? -1 : buffer.limit()));
                    } catch (Throwable e) {
                        XposedBridge.log(TAG + " message-log-error=" + e.getClass().getSimpleName());
                    }
                }
            });
            count++;
        }
        XposedBridge.log(TAG + " hook=" + name + " direction=" + direction + " methods=" + count);
    }

    private static void hookFlutterLoader(ClassLoader loader) {
        try {
            Class<?> type = Class.forName(
                    "io.flutter.embedding.engine.loader.FlutterLoader", false, loader);
            int count = 0;
            for (Method method : type.getDeclaredMethods()) {
                if (!"getLookupKeyForAsset".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 0 || params[0] != String.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        String input = p.args == null ? "" : String.valueOf(p.args[0]);
                        String output = String.valueOf(p.getResult());
                        String warningKey = extractWarningKey(input);
                        if (warningKey == null) warningKey = extractWarningKey(output);
                        if (warningKey != null) publishWarningAsset(warningKey);
                        if (isRoadSign(input) || isRoadSign(output))
                            XposedBridge.log(TAG + " assetLookup input=" + input + " result=" + output);
                    }
                });
                count++;
            }
            XposedBridge.log(TAG + " FlutterLoader assetLookupMethods=" + count);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " FlutterLoader unavailable=" + e.getClass().getSimpleName());
        }
    }

    private static void hookAssetManager() {
        hookAssetMethod("open");
        hookAssetMethod("openFd");
    }

    private static void hookAssetMethod(String methodName) {
        try {
            XposedBridge.hookAllMethods(AssetManager.class, methodName, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length == 0 || !(p.args[0] instanceof String)) return;
                    String path = (String) p.args[0];
                    String warningKey = extractWarningKey(path);
                    if (warningKey != null) publishWarningAsset(warningKey);
                    if (isRoadSign(path)) XposedBridge.log(TAG + " assetOpen path=" + path);
                }
            });
            XposedBridge.log(TAG + " AssetManager." + methodName + " observer ready");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " AssetManager." + methodName + " unavailable="
                    + e.getClass().getSimpleName());
        }
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
        if (lower.contains("settings/speed_sign.png")) return "speed:50";
        return null;
    }

    private static void publishWarningAsset(String key) {
        Context context = appContext;
        if (context == null || key == null || !PUBLISHED.add(key)) return;
        PUBLISHER.execute(() -> {
            try {
                Bundle extras = new Bundle();
                extras.putString("asset_key", key);
                Uri uri = Uri.parse("content://com.carassistant.v10.wynwarning");
                Bundle result = context.getContentResolver().call(uri, "publish_asset", null, extras);
                if (result == null || !result.getBoolean("accepted", false)) {
                    PUBLISHED.remove(key);
                }
            } catch (Throwable error) {
                PUBLISHED.remove(key);
                XposedBridge.log(TAG + " asset bridge failed key=" + key
                        + " error=" + error.getClass().getSimpleName());
            }
        });
    }

    private static boolean isRoadSign(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("road_sign_");
    }
}
