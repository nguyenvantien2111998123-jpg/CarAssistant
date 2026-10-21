package com.carassistant.v10.hook;

import android.view.View;
import android.view.ViewGroup;
import android.util.Log;

import java.util.Locale;

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

    private GearheadMediaTapProbe() { }

    public static void install(ClassLoader classLoader) {
        if (installed) return;
        try {
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
                out.append(" desc=").append(trim(description.toString()));
            }
            if (current instanceof android.widget.TextView) {
                CharSequence text = ((android.widget.TextView) current).getText();
                if (text != null && text.length() > 0) out.append(" text=").append(trim(text.toString()));
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
