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
 * Steering-wheel media-key adapter installed in Android Auto (Gearhead).
 *
 * It consumes only MEDIA_NEXT and MEDIA_PREVIOUS while arbitrating short taps
 * versus holds. Unknown media keys are observed but never rewritten.
 * Touch/display hooks are intentionally untouched.
 */
public final class SteeringWheelKeyHooks {
    private static final String TAG = "[AACast SteeringKey]";
    private static final String SELF = "com.carassistant.v10";
    private static final String VOICE_ACTION = "com.carassistant.v10.STEERING_VOICE";

    private static final long DOUBLE_PRESS_WINDOW_MS = 300L;
    private static final long HOLD_START_MS = 1000L;
    private static final long HOLD_REPEAT_MS = 1000L;

    private static final int NEXT = KeyEvent.KEYCODE_MEDIA_NEXT;
    private static final int PREVIOUS = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    private static final int FAST_FORWARD = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
    private static final int REWIND = KeyEvent.KEYCODE_MEDIA_REWIND;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static int pressedKey = KeyEvent.KEYCODE_UNKNOWN;
    private static long pressedDownTime;
    private static boolean pressedHeld;
    private static boolean pressedIsDoubleTap;
    private static int holdRepeatCount;
    private static Runnable holdStartRunnable;
    private static Runnable holdRepeatRunnable;

    private static boolean pendingNext;
    private static long firstNextUpTime;
    private static Runnable pendingSingleNext;

    private static final int[] CANDIDATE_CODES = {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            NEXT,
            PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            FAST_FORWARD,
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

                            if (event.getKeyCode() == NEXT || event.getKeyCode() == PREVIOUS) {
                                if (!isV10MediaRouteActive()) {
                                    // Keep the native path when an independent Android Auto media app owns playback.
                                    return;
                                }
                                arbitrate(event, param);
                            }
                        }
                    });
            XposedBridge.log(TAG + " media-key adapter installed; double="
                    + DOUBLE_PRESS_WINDOW_MS + "ms, hold=" + HOLD_START_MS + "ms");
        } catch (Throwable error) {
            XposedBridge.log(TAG + " hook install failed: " + error);
        }
    }

    private static void arbitrate(KeyEvent event, XC_MethodHook.MethodHookParam param) {
        final int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            param.setResult(true);
            if (event.getRepeatCount() != 0) return;

            synchronized (LOCK) {
                long now = SystemClock.uptimeMillis();

                if (key == NEXT && pendingNext) {
                    long elapsed = now - firstNextUpTime;
                    if (elapsed >= 0 && elapsed <= DOUBLE_PRESS_WINDOW_MS) {
                        cancelPendingNextLocked();
                        pressedKey = NEXT;
                        pressedDownTime = event.getDownTime();
                        pressedHeld = false;
                        pressedIsDoubleTap = true;
                        cancelHoldRunnablesLocked();
                        XposedBridge.log(TAG + " double NEXT -> voice assistant");
                        requestVoice();
                        return;
                    }

                    // The previous single tap's timer was delayed; flush it before
                    // beginning a new independent press.
                    cancelPendingNextLocked();
                    dispatchMediaKey(NEXT, false, 0L, 0);
                }

                // Ignore overlapping key-downs rather than corrupting press state.
                if (pressedKey != KeyEvent.KEYCODE_UNKNOWN) return;

                pressedKey = key;
                pressedDownTime = event.getDownTime();
                pressedHeld = false;
                pressedIsDoubleTap = false;
                holdRepeatCount = 0;

                holdStartRunnable = () -> startHoldIfStillPressed(key);
                MAIN.postDelayed(holdStartRunnable, HOLD_START_MS);
            }
            return;
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            param.setResult(true);
            synchronized (LOCK) {
                if (pressedKey != key) return;

                cancelHoldRunnablesLocked();
                if (pressedHeld) {
                    XposedBridge.log(TAG + " seek hold released key=" + key
                            + " repeats=" + holdRepeatCount);
                } else if (!pressedIsDoubleTap) {
                    if (key == NEXT) {
                        scheduleSingleNextLocked();
                    } else {
                        dispatchMediaKey(PREVIOUS, false, 0L, 0);
                    }
                }

                pressedKey = KeyEvent.KEYCODE_UNKNOWN;
                pressedDownTime = 0L;
                pressedHeld = false;
                pressedIsDoubleTap = false;
                holdRepeatCount = 0;
            }
        }
    }

    private static void scheduleSingleNextLocked() {
        cancelPendingNextLocked();
        pendingNext = true;
        firstNextUpTime = SystemClock.uptimeMillis();
        pendingSingleNext = () -> {
            synchronized (LOCK) {
                if (!pendingNext) return;
                pendingNext = false;
                pendingSingleNext = null;
                XposedBridge.log(TAG + " single NEXT -> active media session");
                dispatchMediaKey(NEXT, false, 0L, 0);
            }
        };
        MAIN.postDelayed(pendingSingleNext, DOUBLE_PRESS_WINDOW_MS);
    }

    private static void startHoldIfStillPressed(int key) {
        synchronized (LOCK) {
            if (pressedKey != key || pressedHeld || pressedIsDoubleTap) return;
            pressedHeld = true;
            holdRepeatCount = 0;
            XposedBridge.log(TAG + " hold started key=" + key + " -> seek 15 seconds");
            requestSeek(key == NEXT ? 1 : -1);
            holdRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    synchronized (LOCK) {
                        if (pressedKey != key || !pressedHeld) return;
                        holdRepeatCount++;
                        requestSeek(key == NEXT ? 1 : -1);
                        MAIN.postDelayed(this, HOLD_REPEAT_MS);
                    }
                }
            };
            MAIN.postDelayed(holdRepeatRunnable, HOLD_REPEAT_MS);
        }
    }

    private static void requestSeek(int direction) {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (!(app instanceof Context)) {
                XposedBridge.log(TAG + " no Gearhead context for seek request");
                return;
            }
            Intent intent = new Intent("com.carassistant.v10.STEERING_MEDIA_SEEK");
            intent.setPackage(SELF);
            intent.putExtra("direction", direction);
            ((Context) app).sendBroadcast(intent);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " seek broadcast failed: " + error);
        }
    }

    private static void cancelHoldRunnablesLocked() {
        if (holdStartRunnable != null) MAIN.removeCallbacks(holdStartRunnable);
        if (holdRepeatRunnable != null) MAIN.removeCallbacks(holdRepeatRunnable);
        holdStartRunnable = null;
        holdRepeatRunnable = null;
    }

    private static void cancelPendingNextLocked() {
        if (pendingSingleNext != null) MAIN.removeCallbacks(pendingSingleNext);
        pendingSingleNext = null;
        pendingNext = false;
    }

    /**
     * Dispatch a complete short press, or a held FF/RW key. The target player
     * decides the seek rate; not all MediaSession implementations support seeking.
     */
    private static void dispatchMediaKey(int keyCode, boolean release,
                                         long originalDownTime, int repeatCount) {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (!(app instanceof Context)) {
                XposedBridge.log(TAG + " no Gearhead application context for media dispatch");
                return;
            }
            AudioManager audio = (AudioManager) ((Context) app)
                    .getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) return;

            long now = SystemClock.uptimeMillis();
            if (release) {
                long down = originalDownTime > 0L ? originalDownTime : now;
                audio.dispatchMediaKeyEvent(new KeyEvent(
                        down, now, KeyEvent.ACTION_UP, keyCode, 0));
                return;
            }

            if (originalDownTime > 0L) {
                audio.dispatchMediaKeyEvent(new KeyEvent(
                        originalDownTime, now, KeyEvent.ACTION_DOWN, keyCode, repeatCount));
                return;
            }

            long down = now;
            audio.dispatchMediaKeyEvent(new KeyEvent(
                    down, down, KeyEvent.ACTION_DOWN, keyCode, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(
                    down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Throwable error) {
            XposedBridge.log(TAG + " media dispatch failed key=" + keyCode + ": " + error);
        }
    }

    private static void requestVoice() {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (!(app instanceof Context)) {
                XposedBridge.log(TAG + " no Gearhead context for voice broadcast");
                return;
            }
            Intent intent = new Intent(VOICE_ACTION);
            intent.setPackage(SELF);
            ((Context) app).sendBroadcast(intent);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " voice broadcast failed: " + error);
        }
    }

    private static boolean isV10MediaRouteActive() {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
            if (!(app instanceof Context)) return false;
            android.database.Cursor cursor = null;
            try {
                cursor = ((Context) app).getContentResolver().query(
                        android.net.Uri.parse("content://com.carassistant.v10.launcher.dock/state"),
                        new String[]{"projection_active", "target_package", "active_media_package"},
                        null, null, null);
                if (cursor == null || !cursor.moveToFirst() || cursor.getInt(0) != 1) return false;
                String targetPackage = cursor.getString(1);
                String mediaPackage = cursor.getString(2);
                return targetPackage != null && !targetPackage.isEmpty()
                        && targetPackage.equals(mediaPackage);
            } finally {
                if (cursor != null) try { cursor.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + " routing state unavailable; preserving native media key path: " + error);
            return false;
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
