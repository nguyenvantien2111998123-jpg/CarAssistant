package com.carassistant.v10.hook;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Diagnostic-only probe for WYN's main, resumed homepage Activity.
 *
 * Deliberately does not hook WindowManager.addView: popup/overlay windows are
 * not the source requested for CarAsistant artwork. This probe records the
 * Activity identity and its own decor-view renderer hierarchy only. It does
 * not launch WYN, inspect text, capture pixels, or modify the app UI.
 */
public final class WynHomeProbe {
    private static final String TAG = "[AACast WYN Home]";
    private static final String PACKAGE = "com.wynauto.wyn";

    private WynHomeProbe() { }

    public static void install(ClassLoader ignored) {
        try {
            int count = XposedBridge.hookAllMethods(Activity.class, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Activity)) return;
                            Activity activity = (Activity) param.thisObject;
                            try {
                                if (!PACKAGE.equals(activity.getPackageName())) return;
                                Window window = activity.getWindow();
                                if (window == null) return;
                                View decor = window.getDecorView();
                                if (decor == null) return;

                                XposedBridge.log(TAG + " resumed activity="
                                        + activity.getClass().getName()
                                        + " component=" + activity.getComponentName()
                                        + " decor=" + decor.getClass().getName());
                                WeakReference<View> root = new WeakReference<>(decor);
                                decor.postDelayed(() -> sample(root.get(), "250ms"), 250L);
                                decor.postDelayed(() -> sample(root.get(), "1000ms"), 1000L);
                                decor.postDelayed(() -> sample(root.get(), "2500ms"), 2500L);
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + " Activity inspection failed: " + error);
                            }
                        }
                    });
            XposedBridge.log(TAG + " installed; Activity.onResume hooks=" + count);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " installation failed: " + error);
        }
    }

    private static void sample(View root, String when) {
        if (root == null) return;
        try {
            StringBuilder out = new StringBuilder(512);
            out.append(TAG).append(" sample=").append(when).append(" decorTree=");
            appendTree(out, root, 0);
            XposedBridge.log(out.toString());
        } catch (Throwable error) {
            XposedBridge.log(TAG + " tree sample failed: " + error);
        }
    }

    private static void appendTree(StringBuilder out, View view, int depth) {
        if (view == null || depth > 10 || out.length() > 4000) return;
        if (depth > 0) out.append('/');
        out.append(view.getClass().getName())
                .append('[').append(view.getWidth()).append('x')
                .append(view.getHeight()).append(']');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = Math.min(group.getChildCount(), 24);
            out.append('{');
            for (int i = 0; i < count; i++) {
                if (i > 0) out.append(',');
                appendTree(out, group.getChildAt(i), depth + 1);
                if (out.length() > 4000) break;
            }
            if (group.getChildCount() > count) {
                out.append(",+").append(group.getChildCount() - count);
            }
            out.append('}');
        }
    }
}
