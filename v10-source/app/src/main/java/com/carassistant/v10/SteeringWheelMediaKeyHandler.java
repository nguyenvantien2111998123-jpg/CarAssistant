package com.carassistant.v10;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Converts raw media-button KeyEvents delivered to CarAsistant's MediaSession
 * into steering-wheel single, long-press and double-press gestures.
 *
 * NEXT single: skip forward (after the double-click window).
 * NEXT double: invoke CarAsistant voice assistant.
 * NEXT hold: seek forward by 15 seconds at hold start and once per second.
 * PREVIOUS single: skip backward.
 * PREVIOUS hold: seek backward by 15 seconds at hold start and once per second.
 */
final class SteeringWheelMediaKeyHandler {
    private static final long DOUBLE_CLICK_MS = 350L;
    private static final long LONG_PRESS_MS = 500L;
    private static final long SEEK_INTERVAL_MS = 1000L;

    interface Actions {
        void onNext();
        void onPrevious();
        void onSeekForward();
        void onSeekBackward();
        void onVoiceAssistant();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Actions actions;
    private final Object lock = new Object();

    private int pressedKey = KeyEvent.KEYCODE_UNKNOWN;
    private boolean longPressStarted;
    private long lastNextClickUp;
    private boolean nextClickPending;

    private final Runnable pendingNextSingle = new Runnable() {
        @Override public void run() {
            synchronized (lock) {
                if (!nextClickPending) return;
                nextClickPending = false;
            }
            actions.onNext();
        }
    };

    private Runnable longPressRunnable;
    private Runnable seekRepeatRunnable;

    SteeringWheelMediaKeyHandler(Actions actions) {
        if (actions == null) throw new IllegalArgumentException("actions == null");
        this.actions = actions;
    }

    boolean handle(Intent intent) {
        if (intent == null) return false;
        KeyEvent event;
        try {
            event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        } catch (RuntimeException error) {
            return false;
        }
        if (event == null) return false;

        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_MEDIA_NEXT
                || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            handleNext(event);
            return true;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || key == KeyEvent.KEYCODE_MEDIA_REWIND) {
            handlePrevious(event);
            return true;
        }
        return false;
    }

    private void handleNext(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() != 0) return;
            synchronized (lock) {
                if (pressedKey != KeyEvent.KEYCODE_UNKNOWN) return;
                pressedKey = KeyEvent.KEYCODE_MEDIA_NEXT;
                longPressStarted = false;
                longPressRunnable = () -> startLongPress(KeyEvent.KEYCODE_MEDIA_NEXT);
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
            }
            return;
        }
        if (event.getAction() != KeyEvent.ACTION_UP) return;

        boolean wasLongPress;
        boolean isDoubleClick = false;
        synchronized (lock) {
            if (pressedKey != KeyEvent.KEYCODE_MEDIA_NEXT) return;
            cancelLongPressLocked();
            wasLongPress = longPressStarted;
            pressedKey = KeyEvent.KEYCODE_UNKNOWN;
            longPressStarted = false;

            if (!wasLongPress) {
                long now = SystemClock.uptimeMillis();
                if (nextClickPending && now - lastNextClickUp <= DOUBLE_CLICK_MS) {
                    handler.removeCallbacks(pendingNextSingle);
                    nextClickPending = false;
                    isDoubleClick = true;
                } else {
                    if (nextClickPending) {
                        handler.removeCallbacks(pendingNextSingle);
                        nextClickPending = false;
                    }
                    nextClickPending = true;
                    lastNextClickUp = now;
                    handler.postDelayed(pendingNextSingle, DOUBLE_CLICK_MS);
                }
            }
        }
        if (isDoubleClick) actions.onVoiceAssistant();
    }

    private void handlePrevious(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() != 0) return;
            synchronized (lock) {
                if (pressedKey != KeyEvent.KEYCODE_UNKNOWN) return;
                pressedKey = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                longPressStarted = false;
                longPressRunnable = () -> startLongPress(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
            }
            return;
        }
        if (event.getAction() != KeyEvent.ACTION_UP) return;

        boolean wasLongPress;
        synchronized (lock) {
            if (pressedKey != KeyEvent.KEYCODE_MEDIA_PREVIOUS) return;
            cancelLongPressLocked();
            wasLongPress = longPressStarted;
            pressedKey = KeyEvent.KEYCODE_UNKNOWN;
            longPressStarted = false;
        }
        if (!wasLongPress) actions.onPrevious();
    }

    private void startLongPress(int key) {
        synchronized (lock) {
            if (pressedKey != key || longPressStarted) return;
            longPressStarted = true;
            if (key == KeyEvent.KEYCODE_MEDIA_NEXT) actions.onSeekForward();
            else actions.onSeekBackward();

            seekRepeatRunnable = new Runnable() {
                @Override public void run() {
                    synchronized (lock) {
                        if (pressedKey != key || !longPressStarted) return;
                        if (key == KeyEvent.KEYCODE_MEDIA_NEXT) actions.onSeekForward();
                        else actions.onSeekBackward();
                        handler.postDelayed(this, SEEK_INTERVAL_MS);
                    }
                }
            };
            handler.postDelayed(seekRepeatRunnable, SEEK_INTERVAL_MS);
        }
    }

    private void cancelLongPressLocked() {
        if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
        if (seekRepeatRunnable != null) handler.removeCallbacks(seekRepeatRunnable);
        longPressRunnable = null;
        seekRepeatRunnable = null;
    }

    void dispose() {
        synchronized (lock) {
            cancelLongPressLocked();
            handler.removeCallbacks(pendingNextSingle);
            nextClickPending = false;
            pressedKey = KeyEvent.KEYCODE_UNKNOWN;
            longPressStarted = false;
        }
    }
}
