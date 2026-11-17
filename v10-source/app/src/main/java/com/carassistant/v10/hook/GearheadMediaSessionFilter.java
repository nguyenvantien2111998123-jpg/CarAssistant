package com.carassistant.v10.hook;

import android.app.Application;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Enforces a fail-closed media-session boundary inside Android Auto (Gearhead).
 * Source-player sessions remain active on the phone; Gearhead receives only
 * CarAsistant's mirrored session, or an empty list while that mirror is absent.
 */
public final class GearheadMediaSessionFilter {
    private static final String TAG = "[AACast MediaPriority]";
    private static final String V10_PACKAGE = "com.carassistant.v10";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static volatile boolean installed;
    private static volatile MediaController lastV10Controller;

    private GearheadMediaSessionFilter() { }

    public static void install() {
        if (installed) return;
        installed = true;
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            if (context != null && GEARHEAD.equals(context.getPackageName())) {
                                installSessionHooks();
                            }
                        }
                    });
            XposedBridge.log(TAG + " application attach hook installed");
        } catch (Throwable error) {
            XposedBridge.log(TAG + " install failed: " + error);
        }
    }

    private static void installSessionHooks() {
        try {
            XposedBridge.hookAllMethods(MediaSessionManager.class, "getActiveSessions",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(filter(param.getResult(), "getActiveSessions"));
                        }
                    });
            XposedBridge.log(TAG + " hooked MediaSessionManager.getActiveSessions");
        } catch (Throwable error) {
            XposedBridge.log(TAG + " getActiveSessions hook failed: " + error);
        }

        try {
            Class<?> wrapper = XposedHelpers.findClassIfExists(
                    "android.media.session.MediaSessionManager$SessionsChangedWrapper", null);
            if (wrapper != null) {
                XposedBridge.hookAllMethods(wrapper, "onActiveSessionsChanged",
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args != null && param.args.length > 0
                                        && param.args[0] instanceof List) {
                                    param.args[0] = filter(param.args[0], "sessionsChanged");
                                }
                            }
                        });
                XposedBridge.log(TAG + " hooked active-session callback");
            } else {
                XposedBridge.log(TAG + " SessionsChangedWrapper not found");
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + " callback hook failed: " + error);
        }
    }

    private static Object filter(Object value, String source) {
        if (!(value instanceof List)) return value;
        List<?> sessions = (List<?>) value;
        ArrayList<MediaController> owned = new ArrayList<>();
        StringBuilder seen = new StringBuilder();
        for (Object item : sessions) {
            if (!(item instanceof MediaController)) continue;
            MediaController controller = (MediaController) item;
            String pkg;
            try {
                pkg = controller.getPackageName();
            } catch (Throwable ignored) {
                continue;
            }
            if (seen.length() > 0) seen.append(',');
            seen.append(pkg);
            if (V10_PACKAGE.equals(pkg)) {
                lastV10Controller = controller;
                owned.add(controller);
            }
        }
        if (!owned.isEmpty()) {
            XposedBridge.log(TAG + " " + source + ": CarAsistant owns Gearhead media; "
                    + sessions.size() + " -> " + owned.size() + "; seen=" + seen);
            return owned;
        }

        MediaController cached = lastV10Controller;
        if (cached != null) {
            try {
                if (V10_PACKAGE.equals(cached.getPackageName())) {
                    cached.getPlaybackState();
                    owned.add(cached);
                    XposedBridge.log(TAG + " " + source
                            + ": retained reachable CarAsistant mirror; suppressed=" + seen);
                    return owned;
                }
            } catch (Throwable error) {
                lastV10Controller = null;
                XposedBridge.log(TAG + " cached CarAsistant mirror unavailable: " + error);
            }
        }

        // Never pass phone-player sessions through to Gearhead as a fallback.
        XposedBridge.log(TAG + " " + source + ": no CarAsistant mirror; returning EMPTY; "
                + "suppressed=" + seen);
        return owned;
    }
}
