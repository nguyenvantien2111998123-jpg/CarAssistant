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
 * Automatically requests the CarAsistant Car App when Android Auto's
 * Coolwalk facet bar is inflated in the :projection process.
 *
 * Uses Gearhead's own Car App launcher (same route as AaUiHook) instead of
 * starting V10CarActivity as a phone Activity. Retries are bounded and
 * duplicate launches are suppressed.
 */
public final class GearheadAutoOpen {
    private static final String TAG = "[AACast AutoOpen]";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final String PACKAGE = "com.carassistant.v10";
    private static final String SERVICE = "com.carassistant.v10.AACastCarService";
    // Same three facet-bar layout variants monitored by AADisplay's AaUiHook.
    private static final String[] FACET_BAR_RESOURCES = {
            "gh_coolwalk_vertical_facet_bar",
            "gh_coolwalk_facet_bar",
            "gh_coolwalk_facet_bar_rhd"
    };

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
        if (classLoader == null || !INSTALLED.compareAndSet(false, true)) return;
        hostClassLoader = classLoader;
        try {
            XposedHelpers.findAndHookMethod(
                    LayoutInflater.class, "inflate",
                    int.class, ViewGroup.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int resourceId = (Integer) param.args[0];
                                LayoutInflater inflater = (LayoutInflater) param.thisObject;
                                Context context = inflater.getContext();
                                Resources resources = context.getResources();
                                if (!isFacetBar(resources, resourceId)) return;

                                Object result = param.getResult();
                                if (!(result instanceof ViewGroup)) {
                                    log("Facet bar inflated without a ViewGroup result");
                                    return;
                                }
                                log("Android Auto facet bar detected; scheduling CarAsistant launch");
                                scheduleOpen((ViewGroup) result);
                            } catch (Throwable error) {
                                log("Facet-bar detection failed safely: " + error);
                            }
                        }
                    });
            log("Facet-bar watcher installed in Gearhead projection process");
        } catch (Throwable error) {
            INSTALLED.set(false);
            log("Could not install facet-bar watcher: " + error);
        }
    }

    private static boolean isFacetBar(Resources resources, int resourceId) {
        try {
            String entryName = resources.getResourceEntryName(resourceId);
            if (!GEARHEAD.equals(resources.getResourcePackageName(resourceId))) return false;
            for (String candidate : FACET_BAR_RESOURCES) {
                if (!candidate.equals(entryName)) continue;
                int expectedId = resources.getIdentifier(candidate, "layout", GEARHEAD);
                // If Gearhead resolves the named resource, require the exact ID;
                // otherwise the exact entry-name + package check is the fallback.
                return expectedId == 0 || expectedId == resourceId;
            }
        } catch (Throwable ignored) {
            // Some OEM/AA builds hide resource names; use resolved IDs below.
        }
        try {
            for (String candidate : FACET_BAR_RESOURCES) {
                int expectedId = resources.getIdentifier(candidate, "layout", GEARHEAD);
                if (expectedId != 0 && expectedId == resourceId) return true;
            }
        } catch (Throwable ignored) {
            // Version-specific resource lookup must never crash Gearhead.
        }
        return false;
    }

    private static void scheduleOpen(ViewGroup anchor) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastSuccessfulOpenElapsed < OPEN_COOLDOWN_MS) return;
        if (!OPEN_PENDING.compareAndSet(false, true)) return;
        boolean posted = anchor.postDelayed(() -> attemptOpen(1), OPEN_DELAY_MS);
        if (!posted) MAIN.postDelayed(() -> attemptOpen(1), OPEN_DELAY_MS);
    }

    private static void attemptOpen(int attempt) {
        try {
            ClassLoader loader = hostClassLoader;
            if (loader == null) throw new IllegalStateException("Gearhead classloader unavailable");
            Class<?> controller = XposedHelpers.findClassIfExists(
                    "com.google.android.projection.gearhead.service.CarSystemUiControllerService",
                    loader);
            if (controller == null) throw new ClassNotFoundException("CarSystemUiControllerService not found");
            Method launcher = findLauncherMethod(controller);
            if (launcher == null) throw new NoSuchMethodException("Static a(Intent) launcher not found");

            launcher.setAccessible(true);
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(PACKAGE, SERVICE));
            intent.putExtra("android.intent.extra.PACKAGE_NAME", PACKAGE);
            launcher.invoke(null, intent);

            lastSuccessfulOpenElapsed = SystemClock.elapsedRealtime();
            OPEN_PENDING.set(false);
            log("Gearhead accepted CarAsistant Car App launch request (attempt " + attempt + ")");
        } catch (Throwable error) {
            log("Auto-open attempt " + attempt + "/" + MAX_ATTEMPTS + " failed: " + error);
            if (attempt < MAX_ATTEMPTS) {
                MAIN.postDelayed(() -> attemptOpen(attempt + 1), RETRY_DELAY_MS);
            } else {
                OPEN_PENDING.set(false);
                log("Auto-open retries exhausted; will wait for next facet-bar creation");
            }
        }
    }

    private static Method findLauncherMethod(Class<?> controller) {
        for (Class<?> type = controller; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        && "a".equals(method.getName())
                        && method.getParameterTypes().length == 1
                        && Intent.class.equals(method.getParameterTypes()[0])) return method;
            }
        }
        return null;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + " " + message);
    }
}
