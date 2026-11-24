package com.carassistant.v10.hook;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Read-only diagnostic for Gemini -> Gearhead -> Car Activity handoff.
 *
 * Hooks only Intent-accepting methods declared by Gearhead's
 * CarSystemUiControllerService. It never changes arguments or results.
 */
public final class GearheadCarActivityTrace {

    private static final String TAG = "AACastGeminiTrace";
    private static final String TARGET_PACKAGE = "com.carassistant.v10";
    private static final String CONTROLLER =
            "com.google.android.projection.gearhead.service.CarSystemUiControllerService";

    private GearheadCarActivityTrace() {
    }

    public static void install(ClassLoader classLoader) {
        try {
            Class<?> type = XposedHelpers.findClassIfExists(CONTROLLER, classLoader);
            if (type == null) {
                log("Controller class not found; trace not installed");
                return;
            }

            Set<String> hooked = new HashSet<>();
            int count = 0;
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (!acceptsIntent(method)) {
                        continue;
                    }
                    String signature = signature(method);
                    if (!hooked.add(signature)) {
                        continue;
                    }
                    try {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                for (Object arg : param.args) {
                                    if (arg instanceof Intent) {
                                        Intent intent = (Intent) arg;
                                        if (isTargetIntent(intent)) {
                                            logInvocation(param.method, intent);
                                        }
                                    }
                                }
                            }
                        });
                        count++;
                    } catch (Throwable error) {
                        log("Could not hook " + signature + ": " + error);
                    }
                }
            }
            log("Read-only Gearhead Car Activity trace installed; hookedMethods=" + count);
        } catch (Throwable error) {
            log("Trace installation failed: " + error);
        }
    }

    private static boolean acceptsIntent(Method method) {
        for (Class<?> parameter : method.getParameterTypes()) {
            if (Intent.class.isAssignableFrom(parameter)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTargetIntent(Intent intent) {
        if (intent == null) return false;
        ComponentName component = intent.getComponent();
        if (component != null && TARGET_PACKAGE.equals(component.getPackageName())) {
            return true;
        }
        if (TARGET_PACKAGE.equals(intent.getPackage())) {
            return true;
        }
        try {
            Bundle extras = intent.getExtras();
            return extras != null && TARGET_PACKAGE.equals(
                    extras.getString("android.intent.extra.PACKAGE_NAME"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void logInvocation(Object methodObject, Intent intent) {
        StringBuilder out = new StringBuilder(512);
        out.append("GEARHEAD_INTENT_METHOD ")
                .append(methodObject instanceof Method ? signature((Method) methodObject)
                        : String.valueOf(methodObject))
                .append("\naction=").append(intent.getAction())
                .append("\ncomponent=").append(intent.getComponent())
                .append("\npackage=").append(intent.getPackage())
                .append("\nflags=0x").append(Integer.toHexString(intent.getFlags()))
                .append("\ncategories=").append(intent.getCategories());

        try {
            Bundle extras = intent.getExtras();
            if (extras == null) {
                out.append("\nextraKeys=[]");
            } else {
                out.append("\nextraKeys=").append(extras.keySet());
                Object target = extras.get("android.intent.extra.PACKAGE_NAME");
                if (target != null) {
                    out.append("\nextra.PACKAGE_NAME=").append(target);
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
                    || className.startsWith("de.robv.android.xposed.")) {
                continue;
            }
            out.append("\n  at ").append(frame);
            if (++written >= 18) break;
        }
        log(out.toString());
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
