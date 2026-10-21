package com.carassistant.v10.hook;

import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManagerGlobal;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Locale;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Diagnostic-only probe for discovering Android Auto's real media-card click path.
 * It deliberately does not change host navigation until the exact target view/path
 * has been observed on the installed Android Auto version.
 */
public final class GearheadMediaTapProbe {
    private static final String TAG = "CarAsstMediaTapProbe";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static volatile boolean installed;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<View> DUMPED_ROOTS = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static final int MAX_TREE_NODES = 90;

    private GearheadMediaTapProbe() { }

    public static void install(ClassLoader classLoader) {
        if (installed) return;
        try {
            // Observe newly attached Android Auto windows and dump a bounded view tree.
            // Diagnostic-only: no layout, visibility, focus, or touch behavior is modified.
            XposedBridge.hookAllMethods(WindowManagerGlobal.class, "addView", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0
                            || !(param.args[0] instanceof View)) return;
                    final View candidate = (View) param.args[0];
                    MAIN.postDelayed(() -> dumpAttachedTree(candidate), 1200L);
                }
            });

            XposedHelpers.findAndHookMethod(View.class, "performClick", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object value = param.thisObject;
                    if (!(value instanceof View)) return;
                    View view = (View) value;
                    try {
                        if (view.getContext() == null
                                || !GEARHEAD.equals(view.getContext().getPackageName())) return;
                        String details = describeChain(view);
                        String lower = details.toLowerCase(Locale.ROOT);
                        if (lower.contains("media") || lower.contains("playback")
                                || lower.contains("album") || lower.contains("track")
                                || lower.contains("song") || lower.contains("player")
                                || lower.contains("browse") || lower.contains("queue")
                                || lower.contains("music") || lower.contains("title")) {
                            String message = "performClick " + details;
                            Log.i(TAG, message);
                            XposedBridge.log("[" + TAG + "] " + message);
                        }
                    } catch (Throwable ignored) {
                        // Diagnostic-only: never interfere with Android Auto click handling.
                    }
                }
            });
            installed = true;
            XposedBridge.log("[" + TAG + "] installed (observe-only; no host UI changes)");
        } catch (Throwable error) {
            XposedBridge.log("[" + TAG + "] install failed: " + error);
        }
    }

    private static void dumpAttachedTree(View root) {
        try {
            if (root == null || !root.isAttachedToWindow() || root.getContext() == null
                    || !GEARHEAD.equals(root.getContext().getPackageName())) return;
            synchronized (DUMPED_ROOTS) {
                if (!DUMPED_ROOTS.add(root)) return;
            }
            StringBuilder out = new StringBuilder(4096);
            out.append("attached-root display=");
            try { out.append(root.getDisplay() == null ? "?" : root.getDisplay().getDisplayId()); }
            catch (Throwable ignored) { out.append("?"); }
            out.append(" size=").append(root.getWidth()).append('x').append(root.getHeight());
            int[] budget = new int[]{MAX_TREE_NODES};
            appendTree(root, out, 0, budget);
            String message = out.toString();
            // Keep each log entry below common logcat truncation limits.
            for (int start = 0; start < message.length(); start += 2800) {
                String chunk = message.substring(start, Math.min(message.length(), start + 2800));
                Log.i(TAG, "VIEW_TREE " + chunk);
                XposedBridge.log("[" + TAG + "] VIEW_TREE " + chunk);
            }
        } catch (Throwable ignored) {
            // Observation must never disrupt Android Auto window attachment.
        }
    }

    private static void appendTree(View view, StringBuilder out, int depth, int[] budget) {
        if (view == null || budget[0]-- <= 0) return;
        out.append("\\n");
        for (int i = 0; i < depth; i++) out.append("  ");
        out.append(view.getClass().getName());
        int id = view.getId();
        if (id != View.NO_ID) {
            try {
                out.append("#").append(view.getResources().getResourcePackageName(id))
                        .append(":").append(view.getResources().getResourceEntryName(id));
            } catch (RuntimeException ignored) { out.append("#id=").append(id); }
        }
        out.append(" [").append(view.getLeft()).append(',').append(view.getTop())
                .append(' ').append(view.getWidth()).append('x').append(view.getHeight())
                .append(" vis=").append(view.getVisibility())
                .append(" click=").append(view.isClickable());
        CharSequence description = view.getContentDescription();
        if (description != null && description.length() > 0)
            out.append(" desc=\\\"").append(trim(description.toString())).append("\\\"");
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            if (text != null && text.length() > 0)
                out.append(" text=\\\"").append(trim(text.toString())).append("\\\"");
        }
        out.append(']');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = Math.min(group.getChildCount(), 30);
            for (int i = 0; i < count && budget[0] > 0; i++)
                appendTree(group.getChildAt(i), out, depth + 1, budget);
        }
    }

    private static String describeChain(View view) {
        StringBuilder out = new StringBuilder();
        View current = view;
        for (int depth = 0; current != null && depth < 5; depth++) {
            if (depth > 0) out.append(" <- ");
            out.append(current.getClass().getName());
            int id = current.getId();
            if (id != View.NO_ID) {
                try {
                    out.append("#").append(current.getResources().getResourceEntryName(id));
                } catch (RuntimeException ignored) {
                    out.append("#id=").append(id);
                }
            }
            CharSequence description = current.getContentDescription();
            if (description != null && description.length() > 0) {
            out.append(" desc=\\\"").append(trim(description.toString())).append("\\\"");
            }
            if (current instanceof android.widget.TextView) {
                CharSequence text = ((android.widget.TextView) current).getText();
                out.append(" text=\\\"").append(trim(text.toString())).append("\\\"");
            }
            if (!(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
        return out.toString();
    }

    private static String trim(String value) {
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }
}
