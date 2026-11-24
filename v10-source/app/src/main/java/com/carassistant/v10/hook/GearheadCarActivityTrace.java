package com.carassistant.v10.hook;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Read-only Gearhead diagnostic for Gemini -> Car Activity hand-off.
 *
 * It observes Intent-accepting methods on CarSystemUiControllerService. To avoid
 * missing an undocumented Gemini path, it records method/Intent metadata for all
 * calls through this narrow controller class, not only already-known target Intents.
 * Extra values and voice/conversation payloads are deliberately not logged.
 */
public final class GearheadCarActivityTrace {
    private static final String TAG = "AACastGeminiTrace";
    private static final String CONTROLLER =
            "com.google.android.projection.gearhead.service.CarSystemUiControllerService";
    private static final AtomicInteger INVOCATION = new AtomicInteger();
    private static volatile boolean installed;

    private GearheadCarActivityTrace() { }

    public static synchronized void install(ClassLoader classLoader) {
        if (installed) {
            log("install requested again; already installed in this Gearhead process");
            return;
        }
        log("INSTALL_BEGIN controller=" + CONTROLLER
                + "; classLoader=" + (classLoader == null ? "null" : classLoader.getClass().getName()));
        try {
            Class<?> type = XposedHelpers.findClassIfExists(CONTROLLER, classLoader);
            if (type == null) {
                log("CONTROLLER_NOT_FOUND; Gearhead scope may be active but controller class is not resolvable yet");
                return;
            }

            Set<String> hooked = new HashSet<>();
            int count = 0;
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (!acceptsIntent(method)) continue;
                    String signature = signature(method);
                    if (!hooked.add(signature)) continue;
                    try {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                int id = INVOCATION.incrementAndGet();
                                boolean foundIntent = false;
                                for (Object arg : param.args) {
                                    if (arg instanceof Intent) {
                                        foundIntent = true;
                                        logIntentInvocation(id, param.method, (Intent) arg);
                                    }
                                }
                                if (!foundIntent) {
                                    log("CONTROLLER_CALL id=" + id + " method=" + signature(param.method)
                                            + " args=" + argumentTypes(param.args));
                                }
                            }
                        });
                        count++;
                        log("HOOKED " + signature);
                    } catch (Throwable error) {
                        log("HOOK_FAILED " + signature + " error=" + error.getClass().getName()
                                + ":" + error.getMessage());
                    }
                }
            }
            installed = count > 0;
            log("INSTALL_RESULT hookedIntentMethods=" + count + "; installed=" + installed);
        } catch (Throwable error) {
            log("INSTALL_FAILED " + error.getClass().getName() + ":" + error.getMessage());
        }
    }

    private static boolean acceptsIntent(Method method) {
        for (Class<?> parameter : method.getParameterTypes()) {
            if (Intent.class.isAssignableFrom(parameter)) return true;
        }
        return false;
    }

    private static void logIntentInvocation(int id, Object methodObject, Intent intent) {
        StringBuilder out = new StringBuilder(768);
        out.append("CONTROLLER_INTENT_CALL id=").append(id)
                .append(" method=").append(methodObject instanceof Method
                        ? signature((Method) methodObject) : String.valueOf(methodObject))
                .append("\naction=").append(intent.getAction())
                .append("\ncomponent=").append(intent.getComponent())
                .append("\npackage=").append(intent.getPackage())
                .append("\nflags=0x").append(Integer.toHexString(intent.getFlags()))
                .append("\ncategories=").append(intent.getCategories());

        try {
            Bundle extras = intent.getExtras();
            out.append("\nextraKeys=").append(extras == null ? "[]" : extras.keySet());
            if (extras != null) {
                Object packageName = extras.get("android.intent.extra.PACKAGE_NAME");
                if (packageName instanceof String) {
                    out.append("\nextra.PACKAGE_NAME=").append(packageName);
                }
            }
        } catch (Throwable error) {
            out.append("\nextras=<unavailable:").append(error.getClass().getSimpleName()).append(">");
        }

        out.append("\ncallStack:");
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int written = 0;
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (className.equals(Thread.class.getName())
                    || className.equals(GearheadCarActivityTrace.class.getName())
                    || className.startsWith("de.robv.android.xposed.")) continue;
            out.append("\n  at ").append(frame);
            if (++written >= 20) break;
        }
        log(out.toString());
    }

    private static String argumentTypes(Object[] args) {
        if (args == null || args.length == 0) return "[]";
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) out.append(',');
            Object arg = args[i];
            out.append(arg == null ? "null" : arg.getClass().getName());
        }
        return out.append(']').toString();
    }

    private static String signature(Method method) {
        StringBuilder value = new StringBuilder();
        value.append(Modifier.toString(method.getModifiers())).append(' ')
                .append(method.getDeclaringClass().getName()).append('#')
                .append(method.getName()).append('(');
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) value.append(',');
            value.append(parameters[i].getName());
        }
        value.append("):").append(method.getReturnType().getName());
        return value.toString();
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log("[" + TAG + "] " + message);
    }
}
