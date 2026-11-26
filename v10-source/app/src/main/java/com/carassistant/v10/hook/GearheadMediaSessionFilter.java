package com.carassistant.v10.hook;

import android.app.Application;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Makes the CarAsistant mirror the only media session visible to Android Auto.
 *
 * The hook is installed only in Gearhead. Source-app sessions remain fully
 * published to Android and remain visible to CarAsistant's NotificationListenerService.
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
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            if (context != null && GEARHEAD.equals(context.getPackageName())) {
                                installSessionHooks();
                            }
                        }
                    });
            XposedBridge.log(TAG + " Application.attach hook installed");
        } catch (Throwable error) {
            XposedBridge.log(TAG + " install failed: " + error);
        }
    }

    private static void installSessionHooks() {
        hookControllerQuery("getActiveSessions");
        // Android 12 exposes this hidden variant; some Gearhead builds call it directly.
        hookControllerQuery("getActiveSessionsForUser");

        try {
            Class<?> wrapper = XposedHelpers.findClassIfExists(
                    "android.media.session.MediaSessionManager$SessionsChangedWrapper", null);
            if (wrapper == null) {
                XposedBridge.log(TAG + " SessionsChangedWrapper not found");
                return;
            }

            // Android 12 does NOT call onActiveSessionsChanged on this wrapper.
            // It converts tokens to controllers inside callOnActiveSessionsChangedListener.
            XposedBridge.hookAllMethods(wrapper, "callOnActiveSessionsChangedListener",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length == 0
                                    || !(param.args[0] instanceof List)) return;
                            try {
                                Context context = (Context) XposedHelpers.getObjectField(
                                        param.thisObject, "mContext");
                                param.args[0] = filterTokenList(param.args[0], context);
                            } catch (Throwable error) {
                                // Fail closed in Gearhead: do not let a competing source session
                                // become the native media surface during callback delivery.
                                param.args[0] = Collections.emptyList();
                                XposedBridge.log(TAG + " callback token filtering failed closed: "
                                        + error);
                            }
                        }
                    });
            XposedBridge.log(TAG + " hooked Android 12 callback token conversion");
        } catch (Throwable error) {
            XposedBridge.log(TAG + " callback hook failed: " + error);
        }
    }

    private static void hookControllerQuery(String methodName) {
        try {
            XposedBridge.hookAllMethods(MediaSessionManager.class, methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(filterControllerList(param.getResult()));
                        }
                    });
            XposedBridge.log(TAG + " hooked MediaSessionManager." + methodName);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " " + methodName + " hook failed: " + error);
        }
    }

    private static Object filterControllerList(Object value) {
        if (!(value instanceof List)) return value;
        List<?> sessions = (List<?>) value;
        ArrayList<MediaController> result = new ArrayList<>();

        for (Object item : sessions) {
            if (!(item instanceof MediaController)) continue;
            MediaController controller = (MediaController) item;
            if (V10_PACKAGE.equals(controller.getPackageName())) {
                lastV10Controller = controller;
                result.add(controller);
            }
        }

        if (result.isEmpty()) {
            MediaController cached = getReachableCachedController();
            if (cached != null) result.add(cached);
        }

        if (result.isEmpty()) {
            // Never expose YouTube/Zing/etc. as Android Auto's fallback media owner.
            // The mirror service will publish its own session; until then Gearhead sees no
            // competing session instead of taking over the media card/widget.
            XposedBridge.log(TAG + " no V10 mirror currently published; suppressing "
                    + sessions.size() + " non-V10 sessions");
        } else {
            XposedBridge.log(TAG + " Gearhead sessions filtered "
                    + sessions.size() + " -> " + result.size() + " (CarAsistant only)");
        }
        return result;
    }

    private static Object filterTokenList(Object value, Context context) {
        if (!(value instanceof List)) return value;
        List<?> tokens = (List<?>) value;
        ArrayList<MediaSession.Token> result = new ArrayList<>();

        if (context != null) {
            for (Object item : tokens) {
                if (!(item instanceof MediaSession.Token)) continue;
                try {
                    MediaController controller = new MediaController(
                            context, (MediaSession.Token) item);
                    if (V10_PACKAGE.equals(controller.getPackageName())) {
                        lastV10Controller = controller;
                        result.add((MediaSession.Token) item);
                    }
                } catch (Throwable error) {
                    XposedBridge.log(TAG + " could not inspect a session token: " + error);
                }
            }
        }

        if (result.isEmpty()) {
            MediaController cached = getReachableCachedController();
            if (cached != null) {
                try {
                    result.add(cached.getSessionToken());
                } catch (Throwable error) {
                    XposedBridge.log(TAG + " cached V10 token unavailable: " + error);
                }
            }
        }

        if (result.isEmpty()) {
            XposedBridge.log(TAG + " callback contains no V10 session; suppressing "
                    + tokens.size() + " competing tokens");
        }
        return result;
    }

    private static MediaController getReachableCachedController() {
        MediaController cached = lastV10Controller;
        if (cached == null) return null;
        try {
            if (!V10_PACKAGE.equals(cached.getPackageName())) {
                lastV10Controller = null;
                return null;
            }
            cached.getPlaybackState();
            return cached;
        } catch (Throwable error) {
            lastV10Controller = null;
            XposedBridge.log(TAG + " cached V10 mirror is no longer reachable: " + error);
            return null;
        }
    }
}
