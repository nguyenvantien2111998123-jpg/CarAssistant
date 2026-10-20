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
 * Starts CarAsistant from inside Android Auto's projection process, following
 * AADisplay's approach: wait for the Coolwalk facet bar to inflate, then call
 * Gearhead's internal CarSystemUiControllerService launcher with our projection
 * CarActivityService component. This avoids launching the phone-side activity.
 *
 * Android Auto is obfuscated and may change the internal method/resource names;
 * every lookup is guarded so a failed hook leaves the host process running.
 */
public final class GearheadAutoOpen {
    private static final String TAG = "[AACast AutoOpen]";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final String PACKAGE = "com.carassistant.v10";
    private static final String SERVICE = "com.carassistant.v10.AACastCarService";
    private static final String FACET_BAR_RESOURCE = "gh_coolwalk_vertical_facet_bar";
    private static final long OPEN_DELAY_MS = 1000L;
    private static final long OPEN_COOLDOWN_MS = 30000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static volatile long lastOpenElapsed;
    private static volatile ClassLoader hostClassLoader;

    private GearheadAutoOpen() {}

    public static void install(ClassLoader classLoader) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        hostClassLoader = classLoader;
        try {
            XposedHelpers.findAndHookMethod(
                    LayoutInflater.class,
                    "inflate",
                    int.class,
                    ViewGroup.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int resourceId = (Integer) param.args[0];
                                LayoutInflater inflater = (LayoutInflater) param.thisObject;
                                Context context = inflater.getContext();
                                Resources resources = context.getResources();
                                String entryName = resources.getResourceEntryName(resourceId);
                                String packageName = resources.getResourcePackageName(resourceId);
                                if (!GEARHEAD.equals(packageName)
                                        || !FACET_BAR_RESOURCE.equals(entryName)) {
                                    return;
                                }
                                scheduleOpen();
                            } catch (Throwable ignored) {
                                // Most inflated layouts are unrelated; ignore resource lookups.
                            }
                        }
                    });
            log("Installed facet-bar watcher in Android Auto projection process");
        } catch (Throwable error) {
            INSTALLED.set(false);
            log("Could not install facet-bar watcher: " + error);
        }
    }

    private static void scheduleOpen() {
        long now = SystemClock.elapsedRealtime();
        synchronized (GearheadAutoOpen.class) {
            if (now - lastOpenElapsed < OPEN_COOLDOWN_MS) {
                return;
            }
            lastOpenElapsed = now;
        }
        MAIN.postDelayed(GearheadAutoOpen::launchCarActivity, OPEN_DELAY_MS);
    }

    private static void launchCarActivity() {
        try {
            ClassLoader loader = hostClassLoader;
            if (loader == null) {
                log("Host classloader unavailable; auto-open skipped");
                return;
            }
            Class<?> controller = XposedHelpers.findClassIfExists(
                    "com.google.android.projection.gearhead.service.CarSystemUiControllerService",
                    loader);
            if (controller == null) {
                log("CarSystemUiControllerService not found; auto-open skipped");
                return;
            }
            Method launcher = null;
            for (Method method : controller.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        && "a".equals(method.getName())
                        && method.getParameterTypes().length == 1
                        && Intent.class.equals(method.getParameterTypes()[0])) {
                    launcher = method;
                    break;
                }
            }
            if (launcher == null) {
                log("Internal launcher method a(Intent) not found; auto-open skipped");
                return;
            }
            launcher.setAccessible(true);
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(PACKAGE, SERVICE));
            intent.putExtra("android.intent.extra.PACKAGE_NAME", PACKAGE);
            launcher.invoke(null, intent);
            log("Requested Android Auto to open CarAsistant projection service");
        } catch (Throwable error) {
            log("Auto-open failed safely: " + error);
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + " " + message);
    }
}
