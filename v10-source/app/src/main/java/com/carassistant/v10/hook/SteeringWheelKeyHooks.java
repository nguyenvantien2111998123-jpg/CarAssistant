package com.carassistant.v10.hook;

import android.view.KeyEvent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Read-only diagnostic hook for steering-wheel/media key delivery inside Gearhead.
 *
 * It deliberately does not consume or rewrite events. The first real-device test
 * must establish which key codes and event phases Android Auto exposes before we
 * add remapping, so unknown car controls and the existing projection input path stay safe.
 */
public final class SteeringWheelKeyHooks {
    private static final String TAG = "[AACast SteeringKey]";
    private static final int[] CANDIDATE_CODES = {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            KeyEvent.KEYCODE_MEDIA_RECORD
    };

    private SteeringWheelKeyHooks() { }

    public static void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    KeyEvent.class,
                    "dispatch",
                    KeyEvent.Callback.class,
                    KeyEvent.DispatcherState.class,
                    Object.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            KeyEvent event = (KeyEvent) param.thisObject;
                            if (event == null || !isCandidate(event.getKeyCode())) return;
                            XposedBridge.log(TAG
                                    + " code=" + event.getKeyCode()
                                    + " action=" + actionName(event.getAction())
                                    + " repeat=" + event.getRepeatCount()
                                    + " device=" + event.getDeviceId()
                                    + " source=0x" + Integer.toHexString(event.getSource())
                                    + " downTime=" + event.getDownTime()
                                    + " eventTime=" + event.getEventTime());
                        }
                    });
            XposedBridge.log(TAG + " diagnostic hook installed");
        } catch (Throwable error) {
            XposedBridge.log(TAG + " hook install failed: " + error);
        }
    }

    private static boolean isCandidate(int keyCode) {
        for (int candidate : CANDIDATE_CODES) {
            if (candidate == keyCode) return true;
        }
        return false;
    }

    private static String actionName(int action) {
        if (action == KeyEvent.ACTION_DOWN) return "DOWN";
        if (action == KeyEvent.ACTION_UP) return "UP";
        if (action == KeyEvent.ACTION_MULTIPLE) return "MULTIPLE";
        return Integer.toString(action);
    }
}
