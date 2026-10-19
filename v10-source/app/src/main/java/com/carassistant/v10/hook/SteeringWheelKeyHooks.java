package com.carassistant.v10.hook;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Maps a quick double press of the steering-wheel NEXT key to CarAsistant voice.
 * A single NEXT press is delayed briefly, then replayed to Android's media routing.
 * No touch/input hooks are changed here.
 */
public final class SteeringWheelKeyHooks {
    private static final String TAG = "[AACast SteeringKey]";
    private static final String SELF = "com.carassistant.v10";
    private static final String VOICE_ACTION = "com.carassistant.v10.STEERING_VOICE";
    private static final long DOUBLE_PRESS_WINDOW_MS = 300L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean pendingNext;
    private static boolean consumeNextUp;
    private static long firstNextTime;
    private static Runnable pendingSingleNext;

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

                            if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_NEXT) {
                                handleNext(event, param);
                            }
                        }
                    });
            XposedBridge.log(TAG + " double-press mapping installed; window="
                    + DOUBLE_PRESS_WINDOW_MS + "ms");
        } catch (Throwable error) {
            XposedBridge.log(TAG + " hook install failed: " + error);
        }
    }

    private static void handleNext(KeyEvent event, MethodHookParam param) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // Consume all repeat events while a Next press is held. A hold is not a double tap.
            consumeNextUp = true;
            param.setResult(true);
            if (event.getRepeatCount() != 0) return;

            long now = SystemClock.uptimeMillis();
            if (pendingNext && now - firstNextTime <= DOUBLE_PRESS_WINDOW_MS) {
                pendingNext = false;
                if (pendingSingleNext != null) MAIN.removeCallbacks(pendingSingleNext);
                pendingSingleNext = null;
                XposedBridge.log(TAG + " double NEXT detected -> request CarAsistant voice");
                requestVoice();
                return;
            }

            // If the previous window has expired but its callback was delayed, flush it first.
            if (pendingNext) {
                pendingNext = false;
                if (pendingSingleNext != null) MAIN.removeCallbacks(pendingSingleNext);
                pendingSingleNext = null;
                dispatchMediaNext();
            }

            pendingNext = true;
            firstNextTime = now;
            pendingSingleNext = () -> {
                if (!pendingNext) return;
                pendingNext = false;
                pendingSingleNext = null;
                XposedBridge.log(TAG + " single NEXT -> replay media key");
                dispatchMediaNext();
            };
            MAIN.postDelayed(pendingSingleNext, DOUBLE_PRESS_WINDOW_MS);
            return;
        }

        if (event.getAction() == KeyEvent.ACTION_UP && consumeNextUp) {
            consumeNextUp = false;
            param.setResult(true);
        }
    }

    private static void requestVoice() {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (!(app instanceof Context)) {
                XposedBridge.log(TAG + " cannot access Gearhead application context");
                return;
            }
            Intent intent = new Intent(VOICE_ACTION);
            intent.setPackage(SELF);
            ((Context) app).sendBroadcast(intent);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " voice broadcast failed: " + error);
        }
    }

    private static void dispatchMediaNext() {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (!(app instanceof Context)) {
                XposedBridge.log(TAG + " cannot access Gearhead context to replay NEXT");
                return;
            }
            AudioManager audio = (AudioManager) ((Context) app).getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) return;
            long now = SystemClock.uptimeMillis();
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_NEXT, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now + 1, KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_NEXT, 0));
        } catch (Throwable error) {
            XposedBridge.log(TAG + " media NEXT replay failed: " + error);
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
