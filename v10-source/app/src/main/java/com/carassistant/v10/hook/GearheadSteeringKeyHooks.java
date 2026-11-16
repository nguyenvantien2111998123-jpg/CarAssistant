package com.carassistant.v10.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.content.ContextWrapper;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Captures Android Auto's own media-button/projected-key receiver in either
 * :car or :projection. This is a fallback independent of an active media app
 * or CarAsistant MediaSession.
 *
 * Based on the AADisplay/AAPwned receiver-hook approach: observe receivers
 * registered for MEDIA_BUTTON or projected.KEY_EVENT, then intercept only the
 * steering-wheel NEXT/PREVIOUS family. Every other key is left untouched.
 */
public final class GearheadSteeringKeyHooks {
    private static final String TAG = "[CarAsistant Gearhead Keys]";
    private static final String SELF = "com.carassistant.v10";
    private static final String ACTION_MEDIA_BUTTON = Intent.ACTION_MEDIA_BUTTON;
    private static final String ACTION_PROJECTED_KEY = "android.intent.action.projected.KEY_EVENT";

    private static final Set<String> HOOKED_RECEIVER_METHODS =
            Collections.synchronizedSet(new HashSet<String>());
    private static final GearheadGestureRouter GESTURES = new GearheadGestureRouter();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static volatile String installedProcess = "unknown";

    private GearheadSteeringKeyHooks() { }

    public static void install() {
        install("unknown");
    }

    public static void install(String processName) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        installedProcess = processName == null ? "unknown" : processName;
        try {
            int count = XposedBridge.hookAllMethods(ContextWrapper.class, "registerReceiver",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            BroadcastReceiver receiver = findReceiver(param.args);
                            IntentFilter filter = findFilter(param.args);
                            if (receiver == null || filter == null || filter.countActions() != 1) {
                                return;
                            }
                            String action = filter.getAction(0);
                            if (!ACTION_MEDIA_BUTTON.equals(action)
                                    && !ACTION_PROJECTED_KEY.equals(action)) {
                                return;
                            }
                            hookReceiver(receiver.getClass(), action);
                        }
                    }).size();
            if (count == 0) INSTALLED.set(false);
            XposedBridge.log(TAG + " registerReceiver observer in "
                    + installedProcess + " overloads=" + count);
        } catch (Throwable error) {
            INSTALLED.set(false);
            XposedBridge.log(TAG + " registerReceiver hook unavailable in "
                    + installedProcess + ": " + error);
        }
    }

    private static BroadcastReceiver findReceiver(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof BroadcastReceiver) return (BroadcastReceiver) arg;
        }
        return null;
    }

    private static IntentFilter findFilter(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof IntentFilter) return (IntentFilter) arg;
        }
        return null;
    }

    private static void hookReceiver(Class<?> receiverClass, String registeredAction) {
        String hookKey = receiverClass.getName() + "#" + registeredAction;
        synchronized (HOOKED_RECEIVER_METHODS) {
            if (!HOOKED_RECEIVER_METHODS.add(hookKey)) return;
        }
        try {
            Method onReceive = findOnReceive(receiverClass);
            if (onReceive == null) {
                XposedBridge.log(TAG + " no onReceive method on " + receiverClass.getName());
                return;
            }
            XposedBridge.hookMethod(onReceive, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 2
                            || !(param.args[0] instanceof Context)
                            || !(param.args[1] instanceof Intent)) {
                        return;
                    }
                    Intent intent = (Intent) param.args[1];
                    if (!registeredAction.equals(intent.getAction())) return;
                    KeyEvent event;
                    try {
                        event = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    } catch (Throwable ignored) {
                        return;
                    }
                    if (!isTargetKey(event)) return;

                    // Only the targeted steering keys are consumed. All other
                    // Android Auto receiver behavior remains unchanged.
                    GESTURES.dispatch((Context) param.args[0], event);
                    param.setResult(null);
                    XposedBridge.log(TAG + " captured in " + installedProcess + ": "
                            + describe(event) + " from " + receiverClass.getName());
                }
            });
            XposedBridge.log(TAG + " hooked in " + installedProcess + ": "
                    + receiverClass.getName() + " for " + registeredAction);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " receiver hook failed for "
                    + receiverClass.getName() + ": " + error);
        }
    }

    private static Method findOnReceive(Class<?> type) {
        for (Class<?> current = type; current != null
                && current != BroadcastReceiver.class; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod("onReceive", Context.class, Intent.class);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static boolean isTargetKey(KeyEvent event) {
        if (event == null) return false;
        int key = event.getKeyCode();
        return key == KeyEvent.KEYCODE_MEDIA_NEXT
                || key == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || key == KeyEvent.KEYCODE_MEDIA_REWIND;
    }

    private static String describe(KeyEvent event) {
        return KeyEvent.keyCodeToString(event.getKeyCode()) + "/"
                + (event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP")
                + "/repeat=" + event.getRepeatCount();
    }

    private static final class GearheadGestureRouter {
        private static final long DOUBLE_CLICK_MS = 350L;
        private static final long LONG_PRESS_MS = 500L;
        private static final long SEEK_INTERVAL_MS = 1000L;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Object lock = new Object();
        private Context context;
        private int pressedKey = KeyEvent.KEYCODE_UNKNOWN;
        private long pressedDownTime;
        private boolean longPressStarted;
        private boolean nextClickPending;
        private boolean secondNextTapCandidate;
        private long lastNextClickUp;
        private Runnable longPressRunnable;
        private Runnable seekRepeatRunnable;

        private final Runnable pendingNextSingle = new Runnable() {
            @Override public void run() {
                synchronized (lock) {
                    if (!nextClickPending) return;
                    nextClickPending = false;
                }
                sendAction("com.carassistant.v10.STEERING_MEDIA_NEXT", 0);
            }
        };

        void dispatch(Context sourceContext, KeyEvent event) {
            if (sourceContext == null || event == null) return;
            context = sourceContext.getApplicationContext() != null
                    ? sourceContext.getApplicationContext() : sourceContext;
            int key = normalizeKey(event.getKeyCode());

            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() != 0) return;
                synchronized (lock) {
                    if (pressedKey != KeyEvent.KEYCODE_UNKNOWN) {
                        // Recover from a missing ACTION_UP. A real hold repeats
                        // with the same downTime; a new press has a new downTime.
                        if (key == pressedKey && event.getDownTime() == pressedDownTime) return;
                        cancelLongPressLocked();
                        pressedKey = KeyEvent.KEYCODE_UNKNOWN;
                        pressedDownTime = 0L;
                        longPressStarted = false;
                        secondNextTapCandidate = false;
                    }
                    long now = SystemClock.uptimeMillis();
                    secondNextTapCandidate = key == KeyEvent.KEYCODE_MEDIA_NEXT
                            && nextClickPending && now - lastNextClickUp <= DOUBLE_CLICK_MS;
                    if (secondNextTapCandidate) {
                        handler.removeCallbacks(pendingNextSingle);
                        nextClickPending = false;
                    }
                    pressedKey = key;
                    pressedDownTime = event.getDownTime();
                    longPressStarted = false;
                    longPressRunnable = () -> startLongPress(key);
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                }
                return;
            }

            if (event.getAction() != KeyEvent.ACTION_UP) return;
            boolean invokeVoice = false;
            boolean invokePrevious = false;
            synchronized (lock) {
                if (pressedKey != key) return;
                cancelLongPressLocked();
                boolean wasLongPress = longPressStarted;
                pressedKey = KeyEvent.KEYCODE_UNKNOWN;
                pressedDownTime = 0L;
                longPressStarted = false;
                if (key == KeyEvent.KEYCODE_MEDIA_NEXT) {
                    if (!wasLongPress && secondNextTapCandidate) {
                        secondNextTapCandidate = false;
                        invokeVoice = true;
                    } else if (!wasLongPress) {
                        nextClickPending = true;
                        lastNextClickUp = SystemClock.uptimeMillis();
                        handler.postDelayed(pendingNextSingle, DOUBLE_CLICK_MS);
                    } else {
                        secondNextTapCandidate = false;
                    }
                } else if (!wasLongPress) {
                    invokePrevious = true;
                }
            }
            if (invokeVoice) {
                sendAction("com.carassistant.v10.STEERING_VOICE", 0);
            } else if (invokePrevious) {
                sendAction("com.carassistant.v10.STEERING_MEDIA_PREVIOUS", 0);
            }
        }

        private void startLongPress(int key) {
            synchronized (lock) {
                if (pressedKey != key || longPressStarted) return;
                longPressStarted = true;
                seekRepeatRunnable = new Runnable() {
                    @Override public void run() {
                        synchronized (lock) {
                            if (pressedKey != key || !longPressStarted) return;
                            handler.postDelayed(this, SEEK_INTERVAL_MS);
                        }
                        sendSeek(key == KeyEvent.KEYCODE_MEDIA_NEXT ? 1 : -1);
                    }
                };
                handler.postDelayed(seekRepeatRunnable, SEEK_INTERVAL_MS);
            }
            sendSeek(key == KeyEvent.KEYCODE_MEDIA_NEXT ? 1 : -1);
        }

        private void sendSeek(int direction) {
            sendAction("com.carassistant.v10.STEERING_MEDIA_SEEK", direction);
        }

        private void sendAction(String action, int direction) {
            Context target = context;
            if (target == null) return;
            try {
                Intent intent = new Intent(action).setPackage(SELF);
                if (direction != 0) intent.putExtra("direction", direction);
                target.sendBroadcast(intent);
                XposedBridge.log(TAG + " dispatched " + action
                        + (direction == 0 ? "" : " direction=" + direction));
            } catch (Throwable error) {
                XposedBridge.log(TAG + " broadcast failed for " + action + ": " + error);
            }
        }

        private void cancelLongPressLocked() {
            if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
            if (seekRepeatRunnable != null) handler.removeCallbacks(seekRepeatRunnable);
            longPressRunnable = null;
            seekRepeatRunnable = null;
        }

        private static int normalizeKey(int keyCode) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                return KeyEvent.KEYCODE_MEDIA_NEXT;
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            }
            return keyCode;
        }
    }
}
