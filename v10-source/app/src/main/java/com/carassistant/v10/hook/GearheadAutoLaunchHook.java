package com.carassistant.v10.hook;

import android.app.Notification;
import android.app.NotificationManager;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Diagnostic stage for Android Auto auto-launch.
 *
 * IMPORTANT:
 * - The previous notification-text heuristic and reflective invocation of the
 *   obfuscated CarSystemUiControllerService.a(Intent) method are intentionally
 *   disabled until the exact Gearhead 17.9.664044 connection callback and
 *   launcher contract have been verified from that APK/runtime.
 * - This class observes Gearhead notification events and class loading only.
 * - It never starts an Activity, Service, broadcast, or projection session.
 */
public final class GearheadAutoLaunchHook {
    private static final String TAG = "[AACast AutoLaunch Verify]";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicInteger NOTIFICATION_LOG_COUNT = new AtomicInteger(0);
    private static final int MAX_NOTIFICATION_LOGS = 160;

    private static final Set<String> LOGGED_CLASSES =
            ConcurrentHashMap.newKeySet();

    private static final String[] CLASS_NAME_MARKERS = {
            "CarConnection",
            "ConnectionState",
            "CarSystemUiControllerService",
            "CarActivity",
            "Projection",
            "Gearhead",
            "Session"
    };

    private GearheadAutoLaunchHook() { }

    public static void install(ClassLoader classLoader) {
        if (!INSTALLED.compareAndSet(false, true)) return;

        try {
            hookGearheadNotifications();
            hookRelevantClassLoads();
            inspectKnownLauncherCandidate(classLoader);

            XposedBridge.log(TAG
                    + " diagnostic hooks installed; auto-launch is deliberately disabled");
        } catch (Throwable error) {
            INSTALLED.set(false);
            XposedBridge.log(TAG + " diagnostic install failed: " + error);
        }
    }

    private static void hookGearheadNotifications() {
        XposedBridge.hookAllMethods(NotificationManager.class, "notify",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (NOTIFICATION_LOG_COUNT.incrementAndGet()
                                    > MAX_NOTIFICATION_LOGS) return;

                            Notification notification = findNotification(param.args);
                            if (notification == null) return;

                            String tag = null;
                            Integer id = null;
                            if (param.args != null) {
                                if (param.args.length == 3
                                        && param.args[0] instanceof String
                                        && param.args[1] instanceof Integer) {
                                    tag = (String) param.args[0];
                                    id = (Integer) param.args[1];
                                } else if (param.args.length >= 2
                                        && param.args[0] instanceof Integer) {
                                    id = (Integer) param.args[0];
                                }
                            }

                            Bundle extras = notification.extras;
                            String title = read(extras, Notification.EXTRA_TITLE);
                            String text = read(extras, Notification.EXTRA_TEXT);
                            String bigText = read(extras, Notification.EXTRA_BIG_TEXT);
                            String subText = read(extras, Notification.EXTRA_SUB_TEXT);
                            String summary = read(extras, Notification.EXTRA_SUMMARY_TEXT);

                            XposedBridge.log(TAG
                                    + " NOTIFY id=" + id
                                    + " tag=" + tag
                                    + " channel=" + safe(notification.getChannelId())
                                    + " category=" + safe(notification.category)
                                    + " ongoing=" + ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0)
                                    + " title=" + title
                                    + " text=" + text
                                    + " bigText=" + bigText
                                    + " subText=" + subText
                                    + " summary=" + summary);
                        } catch (Throwable error) {
                            XposedBridge.log(TAG + " notification inspection error: " + error);
                        }
                    }
                });
    }

    private static Notification findNotification(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof Notification) return (Notification) arg;
        }
        return null;
    }

    private static String read(Bundle extras, String key) {
        if (extras == null) return "";
        CharSequence value = extras.getCharSequence(key);
        return value == null ? "" : value.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void hookRelevantClassLoads() {
        XposedBridge.hookAllMethods(ClassLoader.class, "loadClass",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!(param.getResult() instanceof Class<?>)) return;
                            Class<?> loaded = (Class<?>) param.getResult();
                            String name = loaded.getName();
                            if (!isRelevantClassName(name)) return;
                            dumpClassOnce(loaded, "CLASS_LOAD");
                        } catch (Throwable error) {
                            XposedBridge.log(TAG + " class-load inspection error: " + error);
                        }
                    }
                });
    }

    private static boolean isRelevantClassName(String name) {
        if (name == null) return false;
        for (String marker : CLASS_NAME_MARKERS) {
            if (name.contains(marker)) return true;
        }
        return false;
    }

    private static void inspectKnownLauncherCandidate(ClassLoader classLoader) {
        // This is only an observation of the name used by the #821 prototype.
        // It is NOT treated as a verified API and is never invoked.
        try {
            Class<?> candidate = Class.forName(
                    "com.google.android.projection.gearhead.service.CarSystemUiControllerService",
                    false, classLoader);
            dumpClassOnce(candidate, "CANDIDATE_ONLY_NOT_VERIFIED");
        } catch (Throwable error) {
            XposedBridge.log(TAG
                    + " candidate CarSystemUiControllerService not resolved: " + error);
        }
    }

    private static void dumpClassOnce(Class<?> type, String source) {
        String name = type.getName();
        if (!LOGGED_CLASSES.add(name)) return;

        StringBuilder out = new StringBuilder();
        out.append(TAG).append(' ').append(source)
                .append(" class=").append(name)
                .append(" loader=").append(type.getClassLoader());

        try {
            for (Method method : type.getDeclaredMethods()) {
                out.append("\n  METHOD ")
                        .append(Modifier.toString(method.getModifiers())).append(' ')
                        .append(method.getReturnType().getName()).append(' ')
                        .append(method.getName()).append('(');
                Class<?>[] parameters = method.getParameterTypes();
                for (int i = 0; i < parameters.length; i++) {
                    if (i > 0) out.append(", ");
                    out.append(parameters[i].getName());
                }
                out.append(')');
            }
            for (Field field : type.getDeclaredFields()) {
                out.append("\n  FIELD ")
                        .append(Modifier.toString(field.getModifiers())).append(' ')
                        .append(field.getType().getName()).append(' ')
                        .append(field.getName());
            }
            if (type.isEnum()) {
                Object[] constants = type.getEnumConstants();
                out.append("\n  ENUM_CONSTANTS=");
                if (constants != null) {
                    for (Object constant : constants) out.append(constant).append(',');
                }
            }
        } catch (Throwable error) {
            out.append("\n  INSPECTION_ERROR=").append(error);
        }

        XposedBridge.log(out.toString());
    }
}
