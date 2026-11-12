package com.carassistant.v10.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Captures physical steering-wheel media keys at Android's input-policy boundary.
 *
 * MediaSessionService.dispatchMediaKeyEvent is too late for some head units: when
 * there is no source MediaSession, Android may never route the AVRCP key there.
 * PhoneWindowManager.interceptKeyBeforeQueueing sees the hardware KeyEvent before
 * media-session selection, so the gesture recognizer remains reachable while idle.
 */
public final class SteeringWheelInputPolicyHooks {
    private static final String TAG = "[CarAsistant SteeringInput]";
    private static final String SELF = "com.carassistant.v10";
    private static final String ACTION = "com.carassistant.v10.STEERING_RAW_KEY";

    private SteeringWheelInputPolicyHooks() { }

    public static void install(ClassLoader loader) {
        try {
            Class<?> policyClass = XposedHelpers.findClass(
                    "com.android.server.policy.PhoneWindowManager", loader);
            int hooked = 0;
            for (java.lang.reflect.Method method : policyClass.getDeclaredMethods()) {
                if (!"interceptKeyBeforeQueueing".equals(method.getName())
                        || method.getReturnType() != int.class) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length < 2 || !KeyEvent.class.isAssignableFrom(types[0])
                        || types[1] != int.class) continue;

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 2
                                || !(param.args[0] instanceof KeyEvent)) return;
                        KeyEvent event = (KeyEvent) param.args[0];
                        if (!isSteeringMediaKey(event)) return;

                        try {
                            Context context = (Context) XposedHelpers.getObjectField(
                                    param.thisObject, "mContext");
                            if (context == null) return;

                            Intent intent = new Intent(ACTION)
                                    .setComponent(new ComponentName(
                                            SELF, SELF + ".V10ControlReceiver"))
                                    .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(event))
                                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                                            | Intent.FLAG_RECEIVER_FOREGROUND);
                            context.sendBroadcast(intent);

                            // The gesture recognizer now owns NEXT/PREVIOUS and their
                            // DOWN/UP pair. Stop policy dispatch to avoid double handling
                            // by the active app or MediaSessionService.
                            param.setResult(0);
                            XposedBridge.log(TAG + " captured "
                                    + describe(event) + " before media-session routing");
                        } catch (Throwable error) {
                            // Fail open: if our app bridge cannot be reached, retain Android's
                            // original key path rather than stealing the steering control.
                            XposedBridge.log(TAG + " capture failed; original path retained: "
                                    + error);
                        }
                    }
                });
                hooked++;
            }

            if (hooked == 0) {
                XposedBridge.log(TAG + " no compatible PhoneWindowManager input-policy method");
            } else {
                XposedBridge.log(TAG + " installed on PhoneWindowManager (" + hooked + ")");
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + " install failed: " + error);
        }
    }

    private static boolean isSteeringMediaKey(KeyEvent event) {
        if (event == null || event.isCanceled()) return false;
        int key = event.getKeyCode();
        return key == KeyEvent.KEYCODE_MEDIA_NEXT
                || key == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || key == KeyEvent.KEYCODE_MEDIA_REWIND;
    }

    private static String describe(KeyEvent event) {
        return KeyEvent.keyCodeToString(event.getKeyCode()) + "/"
                + (event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP")
                + "/repeat=" + event.getRepeatCount()
                + "/device=" + event.getDeviceId();
    }
}

