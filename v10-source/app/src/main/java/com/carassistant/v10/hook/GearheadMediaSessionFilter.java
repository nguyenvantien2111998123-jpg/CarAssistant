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
 * Limits Android Auto's view of active media sessions while CarAsistant V10
 * owns the projected-display media experience.
 *
 * This runs only in the Gearhead process. It never deactivates or edits the
 * source application's MediaSession, so phone notifications and playback
 * controls remain untouched.
 */
public final class GearheadMediaSessionFilter {

    private static final String TAG = "[AACast MediaPriority]";
    private static final String V10_PACKAGE = "com.carassistant.v10";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static volatile boolean installed;

    private GearheadMediaSessionFilter() {
    }

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
            XposedBridge.log(TAG + " application attach hook installed");
        } catch (Throwable error) {
            XposedBridge.log(TAG + " install failed: " + error);
        }
    }

    private static void installSessionHooks() {
        try {
            XposedBridge.hookAllMethods(MediaSessionManager.class, "getActiveSessions",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(filterIfV10OwnsProjection(param.getResult()));
                        }
                    });
            XposedBridge.log(TAG + " hooked MediaSessionManager.getActiveSessions");
        } catch (Throwable error) {
            XposedBridge.log(TAG + " getActiveSessions hook failed: " + error);
        }

        // Active-session updates are delivered through this framework wrapper.
        // Filter its list before it reaches Android Auto's registered listener.
        try {
            Class<?> wrapper = XposedHelpers.findClassIfExists(
                    "android.media.session.MediaSessionManager$SessionsChangedWrapper", null);
            if (wrapper != null) {
                XposedBridge.hookAllMethods(wrapper, "onActiveSessionsChanged",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args != null && param.args.length > 0
                                        && param.args[0] instanceof List) {
                                    param.args[0] = filterIfV10OwnsProjection(param.args[0]);
                                }
                            }
                        });
                XposedBridge.log(TAG + " hooked active-session change callback");
            } else {
                XposedBridge.log(TAG + " framework callback wrapper not found; "
                        + "query filtering remains enabled");
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + " callback hook unavailable: " + error);
        }
    }

    /**
     * Whenever V10 publishes its mirrored session, make it the sole session
     * visible to Android Auto. This covers phone-launched players and players
     * launched through V10, regardless of whether a projection lease is active.
     */
    private static Object filterIfV10OwnsProjection(Object value) {
        if (!(value instanceof List)) return value;

        List<?> sessions = (List<?>) value;
        boolean hasV10Session = false;
        for (Object item : sessions) {
            if (item instanceof MediaController
                    && V10_PACKAGE.equals(((MediaController) item).getPackageName())) {
                hasV10Session = true;
                break;
            }
        }
        if (!hasV10Session) return value;

        ArrayList<MediaController> filtered = new ArrayList<>();
        for (Object item : sessions) {
            if (item instanceof MediaController
                    && V10_PACKAGE.equals(((MediaController) item).getPackageName())) {
                filtered.add((MediaController) item);
            }
        }
        XposedBridge.log(TAG + " V10 mirror active; filtered Gearhead session list "
                + sessions.size() + " -> " + filtered.size());
        return filtered;
    }

}
