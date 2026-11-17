package com.carassistant.v10.hook;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Starts the registered legacy CarActivity through Gearhead's own launcher
 * after Gearhead posts its explicit Android Auto connected notification.
 *
 * This deliberately does not call startService() on CarActivityService:
 * CarActivityService is a host-bound entry point, and Gearhead must own the
 * transition onto the car display.
 */
public final class GearheadAutoLaunchHook {
    private static final String TAG = "[AACast AutoLaunch]";
    private static final String GEARHEAD =
            "com.google.android.projection.gearhead";
    private static final String SELF = "com.carassistant.v10";
    private static final String LAUNCHER_CLASS =
            "com.google.android.projection.gearhead.service.CarSystemUiControllerService";
    private static final long LAUNCH_COOLDOWN_MS = 30_000L;
    private static final long LAUNCH_SETTLE_MS = 1_200L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static volatile long lastLaunchAt;
    private static volatile boolean launchPending;

    private GearheadAutoLaunchHook() { }

    public static void install(ClassLoader classLoader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            XposedBridge.hookAllMethods(NotificationManager.class, "notify",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Notification notification = findNotification(param.args);
                            if (notification == null || !isConnectedNotification(notification)) return;
                            scheduleCarActivityLaunch(classLoader);
                        }
                    });
            XposedBridge.log(TAG + " installed: observing Gearhead connected notifications");
        } catch (Throwable error) {
            INSTALLED.set(false);
            XposedBridge.log(TAG + " install failed open: " + error);
        }
    }

    private static Notification findNotification(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof Notification) return (Notification) arg;
        }
        return null;
    }

    private static boolean isConnectedNotification(Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return false;
        StringBuilder text = new StringBuilder();
        append(text, extras.getCharSequence(Notification.EXTRA_TITLE));
        append(text, extras.getCharSequence(Notification.EXTRA_TEXT));
        append(text, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        append(text, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        append(text, extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        String message = text.toString().toLowerCase(Locale.ROOT);
        boolean disconnected = message.contains("disconnected")
                || message.contains("not connected")
                || message.contains("đã ngắt kết nối")
                || message.contains("mất kết nối");
        boolean connected = !disconnected && (message.contains("connected")
                || message.contains("đã kết nối")
                || message.contains("connecté")
                || message.contains("verbunden"));
        return connected;
    }

    private static void append(StringBuilder out, CharSequence value) {
        if (value == null || value.length() == 0) return;
        if (out.length() > 0) out.append(' ');
        out.append(value);
    }

    private static void scheduleCarActivityLaunch(ClassLoader classLoader) {
        long now = SystemClock.elapsedRealtime();
        if (launchPending || (lastLaunchAt > 0L && now - lastLaunchAt < LAUNCH_COOLDOWN_MS)) {
            return;
        }
        launchPending = true;
        MAIN.postDelayed(() -> {
            try {
                invokeGearheadCarActivityLauncher(classLoader);
                lastLaunchAt = SystemClock.elapsedRealtime();
            } catch (Throwable error) {
                XposedBridge.log(TAG + " launch failed; Gearhead remains in control: " + error);
            } finally {
                launchPending = false;
            }
        }, LAUNCH_SETTLE_MS);
    }

    private static void invokeGearheadCarActivityLauncher(ClassLoader classLoader)
            throws Exception {
        Class<?> controller = XposedHelpers.findClassIfExists(LAUNCHER_CLASS, classLoader);
        if (controller == null) throw new ClassNotFoundException(LAUNCHER_CLASS);

        Method launcher = null;
        for (Class<?> type = controller; type != null && launcher == null;
             type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        && "a".equals(method.getName())
                        && method.getParameterTypes().length == 1
                        && Intent.class.equals(method.getParameterTypes()[0])) {
                    launcher = method;
                    break;
                }
            }
        }
        if (launcher == null) {
            throw new NoSuchMethodException(
                    "Gearhead CarSystemUiControllerService static launcher(Intent) not found");
        }

        launcher.setAccessible(true);
        Intent carIntent = new Intent();
        carIntent.setComponent(new ComponentName(
                SELF, "com.carassistant.v10.AACastCarService"));
        carIntent.putExtra("android.intent.extra.PACKAGE_NAME", SELF);
        launcher.invoke(null, carIntent);
        XposedBridge.log(TAG + " requested AACastCarService through Gearhead launcher");
    }
}
