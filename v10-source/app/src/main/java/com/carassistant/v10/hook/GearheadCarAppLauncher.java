package com.carassistant.v10.hook;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Starts the registered CarAsistant CarActivity through the same Gearhead
 * entry point used by AADisplay's AaUiHook auto-open path.
 */
public final class GearheadCarAppLauncher {
    private static final String TAG = "[AACast CarLauncher]";
    private static final String PACKAGE = "com.carassistant.v10";
    private static final String SERVICE = "com.carassistant.v10.AACastCarService";
    private static final long RETRY_DELAY_MS = 800L;
    private static final long COOLDOWN_MS = 6000L;
    private static final int MAX_ATTEMPTS = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean PENDING = new AtomicBoolean(false);
    private static volatile ClassLoader hostClassLoader;
    private static volatile long lastOpenElapsed;

    private GearheadCarAppLauncher() { }

    public static void install(ClassLoader classLoader) {
        if (classLoader != null) hostClassLoader = classLoader;
    }

    public static void open() {
        long now = SystemClock.elapsedRealtime();
        // elapsedRealtime() starts near zero after a reboot; zero means no prior
        // launch, not a launch inside the cooldown window.
        if ((lastOpenElapsed > 0L && now - lastOpenElapsed < COOLDOWN_MS)
                || !PENDING.compareAndSet(false, true)) return;
        MAIN.post(() -> attemptOpen(1));
    }

    private static void attemptOpen(int attempt) {
        try {
            ClassLoader loader = hostClassLoader;
            if (loader == null) throw new IllegalStateException("Gearhead classloader unavailable");
            Class<?> controller = XposedHelpers.findClassIfExists(
                    "com.google.android.projection.gearhead.service.CarSystemUiControllerService",
                    loader);
            if (controller == null) throw new ClassNotFoundException("CarSystemUiControllerService");
            Method launcher = findLauncherMethod(controller);
            if (launcher == null) throw new NoSuchMethodException("static a(Intent)");
            launcher.setAccessible(true);
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(PACKAGE, SERVICE));
            intent.putExtra("android.intent.extra.PACKAGE_NAME", PACKAGE);
            launcher.invoke(null, intent);
            lastOpenElapsed = SystemClock.elapsedRealtime();
            log("Requested CarAsistant CarActivity through Gearhead's CarSystemUiControllerService");
            PENDING.set(false);
        } catch (Throwable error) {
            log("Auto-open attempt " + attempt + "/" + MAX_ATTEMPTS + " failed: " + error);
            if (attempt < MAX_ATTEMPTS) {
                MAIN.postDelayed(() -> attemptOpen(attempt + 1), RETRY_DELAY_MS);
            } else {
                PENDING.set(false);
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
