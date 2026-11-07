package com.carassistant.v10.hook;

import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Diagnostic-only probe for WYN's existing overlay windows.
 *
 * It does not launch WYN, change its views, read text/images, or intercept
 * media/steering events. It records only window layout metadata and view class
 * names so the actual Flutter overlay rendering surface can be identified
 * before implementing an image bridge.
 */
public final class WynOverlayProbe {
    private static final String TAG = "[AACast WYN Probe]";
    private static final String WYN_PACKAGE = "com.wynauto.wyn";

    private WynOverlayProbe() { }

    public static void install(ClassLoader classLoader) {
        try {
            Class<?> target = XposedHelpers.findClassIfExists(
                    "android.view.WindowManagerImpl", classLoader);
            if (target == null) {
                XposedBridge.log(TAG + " WindowManagerImpl not found; probe skipped");
                return;
            }
            int count = XposedBridge.hookAllMethods(target, "addView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 2
                                || !(param.args[0] instanceof View)
                                || !(param.args[1] instanceof ViewGroup.LayoutParams)) {
                            return;
                        }
                        View root = (View) param.args[0];
                        ViewGroup.LayoutParams raw = (ViewGroup.LayoutParams) param.args[1];
                        StringBuilder line = new StringBuilder(256);
                        line.append(TAG).append(" addView")
                                .append(" root=").append(root.getClass().getName())
                                .append(" size=").append(raw.width).append('x').append(raw.height);
                        if (raw instanceof WindowManager.LayoutParams) {
                            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) raw;
                            line.append(" type=").append(lp.type)
                                    .append(" flags=0x").append(Integer.toHexString(lp.flags))
                                    .append(" format=").append(lp.format)
                                    .append(" gravity=").append(lp.gravity);
                        }
                        line.append(" tree=");
                        appendViewTree(line, root, 0);
                        XposedBridge.log(line.toString());
                    } catch (Throwable error) {
                        XposedBridge.log(TAG + " addView inspection failed: " + error);
                    }
                }
            });
            XposedBridge.log(TAG + " installed in " + WYN_PACKAGE
                    + "; WindowManagerImpl.addView hooks=" + count);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " installation failed: " + error);
        }
    }

    private static void appendViewTree(StringBuilder out, View view, int depth) {
        if (view == null || depth > 3) return;
        if (out.length() > 1800) {
            out.append("…");
            return;
        }
        if (depth > 0) out.append('/');
        out.append(view.getClass().getSimpleName())
                .append('[').append(view.getWidth()).append('x').append(view.getHeight()).append(']');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = Math.min(group.getChildCount(), 12);
            out.append('{');
            for (int i = 0; i < count; i++) {
                if (i > 0) out.append(',');
                appendViewTree(out, group.getChildAt(i), depth + 1);
                if (out.length() > 1800) break;
            }
            if (group.getChildCount() > count) out.append(",+").append(group.getChildCount() - count);
            out.append('}');
        }
    }
}
