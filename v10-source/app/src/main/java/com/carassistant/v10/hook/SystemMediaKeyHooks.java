package com.carassistant.v10.hook;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Routes steering-wheel media keys before Android's normal MediaSession dispatch.
 * Single NEXT/PREVIOUS and long-hold seek retain the existing CarAsistant media
 * behavior; double NEXT requests the V10 voice assistant.
 */
public final class SystemMediaKeyHooks {
    private static final String TAG = "[AACast SystemMediaKey]";
    private static final String SELF = "com.carassistant.v10";
    private static final long DOUBLE_MS = 300L;
    private static final long HOLD_MS = 1000L;
    private static final long SEEK_REPEAT_MS = 1000L;
    private static final int NEXT = KeyEvent.KEYCODE_MEDIA_NEXT;
    private static final int PREVIOUS = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private static Context systemContext;
    private static int pressedKey = KeyEvent.KEYCODE_UNKNOWN;
    private static boolean held;
    private static boolean doubleTap;
    private static boolean pendingNext;
    private static long firstNextUp;
    private static Runnable pendingSingle;
    private static Runnable holdStart;
    private static Runnable seekRepeat;

    private SystemMediaKeyHooks() { }

    public static void install(ClassLoader loader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> service = XposedHelpers.findClass(
                    "com.android.server.media.MediaSessionService", loader);
            int count = 0;
            for (Method method : service.getDeclaredMethods()) {
                if (!"dispatchMediaKeyEvent".equals(method.getName())) continue;
                boolean hasKeyEvent = false;
                for (Class<?> type : method.getParameterTypes()) {
                    if (KeyEvent.class.isAssignableFrom(type)) hasKeyEvent = true;
                }
                if (!hasKeyEvent) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        KeyEvent event = null;
                        for (Object arg : param.args) {
                            if (arg instanceof KeyEvent) { event = (KeyEvent) arg; break; }
                        }
                        if (event == null) return;
                        int key = event.getKeyCode();
                        if (key != NEXT && key != PREVIOUS) return;
                        try {
                            Object value = XposedHelpers.getObjectField(param.thisObject, "mContext");
                            if (value instanceof Context) systemContext = (Context) value;
                        } catch (Throwable ignored) { }
                        // Prevent premature track skip until tap/hold arbitration completes.
                        param.setResult(null);
                        arbitrate(event);
                    }
                });
                count++;
            }
            if (count == 0) {
                INSTALLED.set(false);
                XposedBridge.log(TAG + " no compatible dispatchMediaKeyEvent entry point");
            } else {
                XposedBridge.log(TAG + " installed on " + count
                        + " system media dispatch method(s)");
            }
        } catch (Throwable error) {
            INSTALLED.set(false);
            XposedBridge.log(TAG + " install failed: " + error);
        }
    }

    private static void arbitrate(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() != 0) return;
            synchronized (LOCK) {
                long now = SystemClock.uptimeMillis();
                if (key == NEXT && pendingNext && now - firstNextUp <= DOUBLE_MS) {
                    cancelPendingNextLocked();
                    pressedKey = NEXT;
                    doubleTap = true;
                    held = false;
                    cancelHoldLocked();
                    send("com.carassistant.v10.STEERING_VOICE", 0);
                    return;
                }
                if (key == NEXT && pendingNext) {
                    cancelPendingNextLocked();
                    send("com.carassistant.v10.STEERING_MEDIA_NEXT", 0);
                }
                if (pressedKey != KeyEvent.KEYCODE_UNKNOWN) return;
                pressedKey = key;
                held = false;
                doubleTap = false;
                holdStart = () -> startHold(key);
                MAIN.postDelayed(holdStart, HOLD_MS);
            }
            return;
        }
        if (event.getAction() != KeyEvent.ACTION_UP) return;
        synchronized (LOCK) {
            if (pressedKey != key) return;
            cancelHoldLocked();
            if (held) {
                // Seek was emitted on hold start and once per second until release.
            } else if (!doubleTap) {
                if (key == NEXT) {
                    pendingNext = true;
                    firstNextUp = SystemClock.uptimeMillis();
                    pendingSingle = () -> {
                        synchronized (LOCK) {
                            if (!pendingNext) return;
                            pendingNext = false;
                            pendingSingle = null;
                            send("com.carassistant.v10.STEERING_MEDIA_NEXT", 0);
                        }
                    };
                    MAIN.postDelayed(pendingSingle, DOUBLE_MS);
                } else {
                    send("com.carassistant.v10.STEERING_MEDIA_PREVIOUS", 0);
                }
            }
            pressedKey = KeyEvent.KEYCODE_UNKNOWN;
            held = false;
            doubleTap = false;
        }
    }

    private static void startHold(int key) {
        synchronized (LOCK) {
            if (pressedKey != key || held || doubleTap) return;
            held = true;
            send("com.carassistant.v10.STEERING_MEDIA_SEEK", key == NEXT ? 1 : -1);
            seekRepeat = new Runnable() {
                @Override public void run() {
                    synchronized (LOCK) {
                        if (pressedKey != key || !held) return;
                        send("com.carassistant.v10.STEERING_MEDIA_SEEK", key == NEXT ? 1 : -1);
                        MAIN.postDelayed(this, SEEK_REPEAT_MS);
                    }
                }
            };
            MAIN.postDelayed(seekRepeat, SEEK_REPEAT_MS);
        }
    }

    private static void cancelHoldLocked() {
        if (holdStart != null) MAIN.removeCallbacks(holdStart);
        if (seekRepeat != null) MAIN.removeCallbacks(seekRepeat);
        holdStart = null;
        seekRepeat = null;
    }

    private static void cancelPendingNextLocked() {
        if (pendingSingle != null) MAIN.removeCallbacks(pendingSingle);
        pendingSingle = null;
        pendingNext = false;
    }

    private static void send(String action, int direction) {
        Context context = systemContext;
        if (context == null) return;
        try {
            Intent intent = new Intent(action);
            intent.setPackage(SELF);
            if (direction != 0) intent.putExtra("direction", direction);
            context.sendBroadcast(intent);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " broadcast failed " + action + ": " + error);
        }
    }
}
