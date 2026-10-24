package com.carassistant.v10.hook;

import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Intercepts media keys at the system_server MediaSessionService dispatch point.
 *
 * Media-session availability is checked from system_server before consuming a new
 * key-down. The controller is captured for the whole key gesture so the delayed
 * single-click and hold actions target the same session that accepted the gesture.
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

    private static volatile Context systemContext;
    private static int pressedKey = KeyEvent.KEYCODE_UNKNOWN;
    private static MediaController pressedController;
    private static MediaController pendingNextController;
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
                            if (arg instanceof KeyEvent) {
                                event = (KeyEvent) arg;
                                break;
                            }
                        }
                        if (event == null) return;
                        int key = event.getKeyCode();
                        if (key != NEXT && key != PREVIOUS) return;

                        try {
                            Object value = XposedHelpers.getObjectField(param.thisObject, "mContext");
                            if (value instanceof Context) systemContext = (Context) value;
                        } catch (Throwable ignored) { }

                        if (handleKeyEvent(event)) {
                            // The decision is made synchronously while MethodParam is valid.
                            param.setResult(null);
                        }
                    }
                });
                count++;
            }
            if (count == 0) {
                INSTALLED.set(false);
                XposedBridge.log(TAG + " no compatible dispatchMediaKeyEvent entry point");
            } else {
                XposedBridge.log(TAG + " installed on " + count + " system media dispatch method(s)");
            }
        } catch (Throwable error) {
            INSTALLED.set(false);
            XposedBridge.log(TAG + " install failed: " + error);
        }
    }

    /**
     * Returns true only when this hook owns the event. A fresh DOWN is passed
     * through untouched if system_server cannot find an active media controller.
     */
    private static boolean handleKeyEvent(KeyEvent event) {
        final int key = event.getKeyCode();
        final int action = event.getAction();

        if (action == KeyEvent.ACTION_UP) {
            synchronized (LOCK) {
                if (pressedKey != key) return false;
                finishPressLocked(key);
                return true;
            }
        }

        if (action != KeyEvent.ACTION_DOWN) return false;

        if (event.getRepeatCount() > 0) {
            synchronized (LOCK) {
                // Repeats belonging to a gesture already consumed at initial DOWN
                // must also be consumed; unrelated repeat events pass through.
                return pressedKey == key;
            }
        }

        // Resolve the double-tap state before querying a new controller: the first
        // NEXT was already consumed and must not be lost if its session changes.
        boolean voiceDoubleTap = false;
        synchronized (LOCK) {
            if (pendingNext && key == NEXT) {
                long delta = SystemClock.uptimeMillis() - firstNextUp;
                if (delta <= DOUBLE_MS) {
                    cancelPendingNextLocked();
                    pressedKey = NEXT;
                    pressedController = pendingNextController;
                    pendingNextController = null;
                    doubleTap = true;
                    held = false;
                    cancelHoldLocked();
                    voiceDoubleTap = true;
                } else {
                    MediaController expiredController = pendingNextController;
                    cancelPendingNextLocked();
                    pendingNextController = null;
                    dispatchNext(expiredController);
                }
            } else if (pendingNext && key != NEXT) {
                // A different key breaks the double-Next sequence. Deliver the
                // first Next once rather than letting a later Next become a false double.
                MediaController expiredController = pendingNextController;
                cancelPendingNextLocked();
                pendingNextController = null;
                dispatchNext(expiredController);
            }
        }
        if (voiceDoubleTap) {
            sendVoiceRequest();
            return true;
        }

        synchronized (LOCK) {
            if (pressedKey != KeyEvent.KEYCODE_UNKNOWN) return false;
        }

        MediaController controller = findActiveController();
        if (controller == null) {
            XposedBridge.log(TAG + " no active media session; passing key=" + key);
            return false;
        }

        synchronized (LOCK) {
            // Re-check after the controller lookup to avoid two concurrent DOWNs
            // claiming the state machine.
            if (pressedKey != KeyEvent.KEYCODE_UNKNOWN) return false;
            pressedKey = key;
            pressedController = controller;
            held = false;
            doubleTap = false;
            holdStart = () -> startHold(key, controller);
            MAIN.postDelayed(holdStart, HOLD_MS);
            return true;
        }
    }

    private static void finishPressLocked(int key) {
        cancelHoldLocked();
        MediaController controller = pressedController;

        if (!held && !doubleTap) {
            if (key == NEXT) {
                pendingNext = true;
                firstNextUp = SystemClock.uptimeMillis();
                pendingNextController = controller;
                pendingSingle = () -> {
                    MediaController target;
                    synchronized (LOCK) {
                        if (!pendingNext) return;
                        pendingNext = false;
                        pendingSingle = null;
                        target = pendingNextController;
                        pendingNextController = null;
                    }
                    dispatchNext(target);
                };
                MAIN.postDelayed(pendingSingle, DOUBLE_MS);
            } else {
                dispatchPrevious(controller);
            }
        }

        pressedKey = KeyEvent.KEYCODE_UNKNOWN;
        pressedController = null;
        held = false;
        doubleTap = false;
    }

    private static void startHold(int key, MediaController controller) {
        synchronized (LOCK) {
            if (pressedKey != key || pressedController != controller || held || doubleTap) return;
            if (!supportsSeek(controller)) {
                // Preserve normal single-key behavior when this player cannot seek.
                return;
            }
            held = true;
            XposedBridge.log(TAG + " hold start -> seek " + (key == NEXT ? "+15s" : "-15s"));
            dispatchSeek(controller, key == NEXT ? 15000L : -15000L);
            seekRepeat = new Runnable() {
                @Override public void run() {
                    synchronized (LOCK) {
                        if (pressedKey != key || pressedController != controller || !held) return;
                        dispatchSeek(controller, key == NEXT ? 15000L : -15000L);
                        MAIN.postDelayed(this, SEEK_REPEAT_MS);
                    }
                }
            };
            MAIN.postDelayed(seekRepeat, SEEK_REPEAT_MS);
        }
    }

    private static MediaController findActiveController() {
        Context context = systemContext;
        if (context == null) return null;
        try {
            MediaSessionManager manager = (MediaSessionManager)
                    context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return null;
            List<MediaController> controllers = manager.getActiveSessions(null);
            if (controllers == null || controllers.isEmpty()) return null;

            MediaController firstWithMetadata = null;
            MediaController firstAvailable = null;
            for (MediaController candidate : controllers) {
                if (candidate == null) continue;
                if (firstAvailable == null) firstAvailable = candidate;
                PlaybackState state = candidate.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    return candidate;
                }
                if (firstWithMetadata == null && candidate.getMetadata() != null) {
                    firstWithMetadata = candidate;
                }
            }
            return firstWithMetadata != null ? firstWithMetadata : firstAvailable;
        } catch (SecurityException error) {
            XposedBridge.log(TAG + " cannot inspect active sessions: " + error);
            return null;
        } catch (RuntimeException error) {
            XposedBridge.log(TAG + " active-session lookup failed: " + error);
            return null;
        }
    }

    private static boolean supportsSeek(MediaController controller) {
        if (controller == null) return false;
        try {
            PlaybackState state = controller.getPlaybackState();
            return state != null
                    && (state.getActions() & PlaybackState.ACTION_SEEK_TO) != 0L;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static void dispatchNext(MediaController controller) {
        if (controller == null) return;
        try {
            controller.getTransportControls().skipToNext();
        } catch (RuntimeException error) {
            XposedBridge.log(TAG + " skipNext failed: " + error);
        }
    }

    private static void dispatchPrevious(MediaController controller) {
        if (controller == null) return;
        try {
            controller.getTransportControls().skipToPrevious();
        } catch (RuntimeException error) {
            XposedBridge.log(TAG + " skipPrevious failed: " + error);
        }
    }

    private static void dispatchSeek(MediaController controller, long offsetMs) {
        if (controller == null || offsetMs == 0L) return;
        try {
            PlaybackState state = controller.getPlaybackState();
            if (state == null || (state.getActions() & PlaybackState.ACTION_SEEK_TO) == 0L) return;
            long position = Math.max(0L, state.getPosition());
            long target = Math.max(0L, position + offsetMs);
            MediaMetadata metadata = controller.getMetadata();
            long duration = metadata == null ? 0L
                    : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (duration > 0L) target = Math.min(target, duration);
            controller.getTransportControls().seekTo(target);
        } catch (RuntimeException error) {
            XposedBridge.log(TAG + " seek failed: " + error);
        }
    }

    private static void sendVoiceRequest() {
        send("com.carassistant.v10.STEERING_VOICE");
    }

    private static void send(String action) {
        Context context = systemContext;
        if (context == null) {
            XposedBridge.log(TAG + " no system context; dropped " + action);
            return;
        }
        try {
            Intent intent = new Intent(action);
            intent.setPackage(SELF);
            context.sendBroadcast(intent);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " broadcast failed " + action + ": " + error);
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
}
