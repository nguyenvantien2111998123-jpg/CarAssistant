package com.carassistant.v10.hook;

import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Diagnostic-only probe for WYN's existing overlay windows.
 *
 * It records only window metadata and rendering-view classes. It never starts
 * WYN, changes its views, reads warning text, captures pixels, or touches media
 * and steering-wheel code.
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
                        final View root = (View) param.args[0];
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
                        XposedBridge.log(line.toString());

                        // Flutter creates/attaches its rendering child asynchronously.
                        // Sample after layout at increasing delays without reading pixels.
                        root.postDelayed(() -> logRenderedTree(root, "250ms"), 250L);
                        root.postDelayed(() -> logRenderedTree(root, "900ms"), 900L);
                        root.postDelayed(() -> logRenderedTree(root, "2200ms"), 2200L);
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

    private static void logRenderedTree(View root, String sample) {
        try {
            StringBuilder out = new StringBuilder(512);
            out.append(TAG).append(" sample=").append(sample).append(" tree=");
            appendViewTree(out, root, 0);
            XposedBridge.log(out.toString());
        } catch (Throwable error) {
            XposedBridge.log(TAG + " tree sample failed: " + error);
        }
    }

    private static void appendViewTree(StringBuilder out, View view, int depth) {
        if (view == null || depth > 8) return;
        if (out.length() > 3500) {
            out.append("…");
            return;
        }
        if (depth > 0) out.append('/');
        out.append(view.getClass().getName())
                .append('[').append(view.getWidth()).append('x').append(view.getHeight()).append(']');
        if (view instanceof SurfaceView) {
            try {
                boolean valid = ((SurfaceView) view).getHolder() != null
                        && ((SurfaceView) view).getHolder().getSurface() != null
                        && ((SurfaceView) view).getHolder().getSurface().isValid();
                out.append("{surfaceValid=").append(valid).append('}');
            } catch (Throwable error) {
                out.append("{surfaceStateError=").append(error.getClass().getSimpleName()).append('}');
            }
        } else if (view instanceof TextureView) {
            out.append("{textureAvailable=").append(((TextureView) view).isAvailable()).append('}');
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = Math.min(group.getChildCount(), 20);
            out.append('{');
            for (int i = 0; i < count; i++) {
                if (i > 0) out.append(',');
                appendViewTree(out, group.getChildAt(i), depth + 1);
                if (out.length() > 3500) break;
            }
            if (group.getChildCount() > count) out.append(",+").append(group.getChildCount() - count);
            out.append('}');
        }
    }
}
