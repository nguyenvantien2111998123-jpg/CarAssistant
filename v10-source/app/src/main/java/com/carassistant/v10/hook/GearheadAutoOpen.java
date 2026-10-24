package com.carassistant.v10.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Auto-opens CarAsistant on the Android Auto display when Coolwalk's facet bar
 * is created. The sequence follows AADisplay's AaUiHook:
 *   1. Watch LayoutInflater for gh_coolwalk_vertical_facet_bar in :projection.
 *   2. Wait for the layout to finish attaching, then delay about one second.
 *   3. Ask Gearhead's CarSystemUiControllerService to launch our CarActivityService.
 *
 * Gearhead is obfuscated and changes between Android Auto versions, so all
 * reflective operations are guarded and retried a small, bounded number of times.
 */
public final class GearheadAutoOpen {
    private static final String TAG = "[AACast AutoOpen]";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final String PACKAGE = "com.carassistant.v10";
    private static final String SERVICE = "com.carassistant.v10.AACastCarService";
    private static final String FACET_BAR_RESOURCE = "gh_coolwalk_vertical_facet_bar";

    private static final long OPEN_DELAY_MS = 1000L;
    private static final long RETRY_DELAY_MS = 1200L;
    private static final long OPEN_COOLDOWN_MS = 30000L;
    private static final int MAX_ATTEMPTS = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean OPEN_PENDING = new AtomicBoolean(false);
    private static volatile long lastSuccessfulOpenElapsed;
    private static volatile ClassLoader hostClassLoader;

    private GearheadAutoOpen() {}

    public static void install(ClassLoader classLoader) {
        if (classLoader == null || !INSTALLED.compareAndSet(false, true)) {
            return;
        }
        hostClassLoader = classLoader;
        try {
            // Android Auto changes which LayoutInflater overload it uses between
            // releases. Hook all overloads, then validate both the resource package/name
            // and the returned view before scheduling anything.
            XposedBridge.hookAllMethods(LayoutInflater.class, "inflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof Integer)) {
                            return;
                        }
                        int resourceId = (Integer) param.args[0];
                        Object result = param.getResult();
                        if (!(result instanceof ViewGroup)) return;

                        LayoutInflater inflater = (LayoutInflater) param.thisObject;
                        Context context = inflater.getContext();
                        Resources resources = context.getResources();
                        int expectedId = resources.getIdentifier(
                                FACET_BAR_RESOURCE, "layout", GEARHEAD);
                        boolean isFacetBar = expectedId != 0
                                ? resourceId == expectedId
                                : isFacetBarByName(resources, resourceId);
                        if (!isFacetBar) return;

                        log("Detected Android Auto Coolwalk facet bar (inflate overload "
                                + param.args.length + "); scheduling auto-open");
                        scheduleOpen((ViewGroup) result);
                    } catch (Throwable error) {
                        log("Facet-bar detection failed safely: " + error);
                    }
                }
            });
            log("Installed AADisplay-style facet-bar watcher in Android Auto projection process");
        } catch (Throwable error) {
            INSTALLED.set(false);
            log("Could not install facet-bar watcher: " + error);
        }
    }

    private static boolean isFacetBarByName(Resources resources, int resourceId) {
        try {
            return FACET_BAR_RESOURCE.equals(resources.getResourceEntryName(resourceId))
                    && GEARHEAD.equals(resources.getResourcePackageName(resourceId));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void scheduleOpen(ViewGroup anchor) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastSuccessfulOpenElapsed < OPEN_COOLDOWN_MS) {
            log("Auto-open suppressed by successful-open cooldown");
            return;
        }
        if (!OPEN_PENDING.compareAndSet(false, true)) {
            log("Auto-open already pending; ignoring duplicate facet-bar inflation");
            return;
        }

        // Posting on the inflated view mirrors AADisplay and ensures Gearhead has
        // completed the UI transaction before we request a car activity.
        boolean posted = anchor.postDelayed(() -> attemptOpen(1), OPEN_DELAY_MS);
        if (!posted) {
            MAIN.postDelayed(() -> attemptOpen(1), OPEN_DELAY_MS);
        }
    }

    private static void attemptOpen(int attempt) {
        try {
            ClassLoader loader = hostClassLoader;
            if (loader == null) {
                throw new IllegalStateException("Android Auto classloader unavailable");
            }

            Class<?> controller = XposedHelpers.findClassIfExists(
                    "com.google.android.projection.gearhead.service.CarSystemUiControllerService",
                    loader);
            if (controller == null) {
                throw new ClassNotFoundException("CarSystemUiControllerService not found");
            }

            Method launcher = findLauncherMethod(controller);
            if (launcher == null) {
                throw new NoSuchMethodException("Static CarSystemUiControllerService.a(Intent) not found");
            }

            launcher.setAccessible(true);
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(PACKAGE, SERVICE));
            intent.putExtra("android.intent.extra.PACKAGE_NAME", PACKAGE);
            launcher.invoke(null, intent);

            lastSuccessfulOpenElapsed = SystemClock.elapsedRealtime();
            log("Requested Gearhead to open CarAsistant projection service (attempt "
                    + attempt + ")");
            OPEN_PENDING.set(false);
        } catch (Throwable error) {
            log("Auto-open attempt " + attempt + "/" + MAX_ATTEMPTS + " failed: " + error);
            if (attempt < MAX_ATTEMPTS) {
                MAIN.postDelayed(() -> attemptOpen(attempt + 1), RETRY_DELAY_MS);
            } else {
                OPEN_PENDING.set(false);
                log("Auto-open stopped after bounded retries; waiting for the next AA UI connection");
            }
        }
    }

    private static Method findLauncherMethod(Class<?> controller) {
        for (Class<?> type = controller; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        && "a".equals(method.getName())
                        && method.getParameterTypes().length == 1
                        && Intent.class.equals(method.getParameterTypes()[0])) {
                    return method;
                }
            }
        }
        return null;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + " " + message);
    }
}
