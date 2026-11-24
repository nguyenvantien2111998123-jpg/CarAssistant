package com.carassistant.v10.hook;

import android.app.Activity;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Trace-only probe for WYN popup windows after its Home Activity is moved
 * behind the phone launcher. It deliberately does not capture pixels, move
 * windows, or change WYN behavior. The observed window type/bounds determine
 * the safe capture source for the next implementation phase.
 */
public final class WynPopupWindowTrace {
    private static final String TAG = "AACastWYNPopupTrace";
    private static final String WYN_PACKAGE = "com.wynauto.wyn";
    private static final Map<View, String> LAST_GEOMETRY =
            Collections.synchronizedMap(new WeakHashMap<View, String>());
    private static volatile boolean installed;

    private WynPopupWindowTrace() { }

    public static synchronized void install(ClassLoader loader) {
        if (installed || loader == null) return;
        installed = true;
        hookActivityLifecycle();
        hookWindowManagerGlobal();
        Log.i(TAG, "probe installed; package=" + WYN_PACKAGE
                + "; mode=trace-only; pixel capture disabled");
    }

    private static void hookActivityLifecycle() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    logActivity("RESUMED", param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    logActivity("PAUSING", param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onStop", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    logActivity("STOPPED", param.thisObject);
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "Activity lifecycle hooks unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static void logActivity(String state, Object candidate) {
        if (!(candidate instanceof Activity)) return;
        Activity activity = (Activity) candidate;
        try {
            if (!WYN_PACKAGE.equals(activity.getPackageName())) return;
            Log.i(TAG, "ACTIVITY " + state
                    + " class=" + activity.getClass().getName()
                    + " taskId=" + activity.getTaskId()
                    + " finishing=" + activity.isFinishing());
        } catch (Throwable ignored) { }
    }

    private static void hookWindowManagerGlobal() {
        try {
            Class<?> type = Class.forName("android.view.WindowManagerGlobal", false, null);
            hookWindowMethod(type, "addView", "ADD");
            hookWindowMethod(type, "updateViewLayout", "UPDATE");
            hookWindowMethod(type, "removeView", "REMOVE");
            hookWindowMethod(type, "removeViewImmediate", "REMOVE_NOW");
        } catch (Throwable error) {
            Log.w(TAG, "WindowManagerGlobal hooks unavailable: "
                    + error.getClass().getSimpleName());
        }
    }

    private static void hookWindowMethod(Class<?> type, String methodName, String action) {
        try {
            XposedBridge.hookAllMethods(type, methodName, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    View view = findView(param.args);
                    if (view == null) return;
                    if ("REMOVE".equals(action) || "REMOVE_NOW".equals(action)) {
                        LAST_GEOMETRY.remove(view);
                        Log.i(TAG, action + " view=" + view.getClass().getName());
                        return;
                    }
                    WindowManager.LayoutParams attrs = findLayoutParams(param.args);
                    logWindow(action, view, attrs);
                    if ("ADD".equals(action)) attachLayoutObserver(view);
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "hook " + methodName + " unavailable: "
                    + error.getClass().getSimpleName());
        }
    }

    private static View findView(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) if (arg instanceof View) return (View) arg;
        return null;
    }

    private static WindowManager.LayoutParams findLayoutParams(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof WindowManager.LayoutParams) {
                return (WindowManager.LayoutParams) arg;
            }
        }
        return null;
    }

    private static void attachLayoutObserver(View view) {
        try {
            Object marker = view.getTag(android.R.id.content);
            if (Boolean.TRUE.equals(marker)) return;
            view.setTag(android.R.id.content, Boolean.TRUE);
            view.addOnLayoutChangeListener((v, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) -> {
                if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                    logWindow("LAYOUT", v, null);
                }
            });
        } catch (Throwable error) {
            Log.d(TAG, "layout observer skipped: " + error.getClass().getSimpleName());
        }
    }

    private static void logWindow(String action, View view, WindowManager.LayoutParams attrs) {
        if (view == null) return;
        try {
            Rect bounds = new Rect();
            boolean hasBounds = view.getGlobalVisibleRect(bounds);
            String geometry = (hasBounds ? bounds.flattenToString() : "not-visible")
                    + "|size=" + view.getWidth() + "x" + view.getHeight()
                    + "|type=" + (attrs == null ? "?" : attrs.type)
                    + "|gravity=" + (attrs == null ? "?" : attrs.gravity)
                    + "|xy=" + (attrs == null ? "?" : attrs.x + "," + attrs.y);
            String previous = LAST_GEOMETRY.put(view, geometry);
            if (geometry.equals(previous) && "LAYOUT".equals(action)) return;

            String children = describeChildren(view, 0);
            Log.i(TAG, action
                    + " class=" + view.getClass().getName()
                    + " bounds=" + geometry
                    + " flags=" + (attrs == null ? "?" : attrs.flags)
                    + " format=" + (attrs == null ? "?" : attrs.format)
                    + " children=" + children);
        } catch (Throwable error) {
            Log.d(TAG, action + " view trace failed: " + error.getClass().getSimpleName());
        }
    }

    private static String describeChildren(View view, int depth) {
        if (!(view instanceof ViewGroup) || depth >= 3) return "";
        ViewGroup group = (ViewGroup) view;
        StringBuilder out = new StringBuilder();
        int count = Math.min(group.getChildCount(), 12);
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            if (out.length() > 0) out.append(',');
            out.append(child.getClass().getSimpleName())
                    .append('[').append(child.getWidth()).append('x')
                    .append(child.getHeight()).append(']');
            if (depth < 2 && child instanceof ViewGroup) {
                out.append('{').append(describeChildren(child, depth + 1)).append('}');
            }
        }
        if (group.getChildCount() > count) out.append(",…");
        return out.toString();
    }
}
