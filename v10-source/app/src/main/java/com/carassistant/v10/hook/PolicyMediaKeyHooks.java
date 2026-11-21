package com.carassistant.v10.hook;

import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Observes media keys at Android's WindowManager policy boundary.
 *
 * Android 12 routes media keys to MediaSessionService only when
 * ACTION_PASS_TO_USER is clear. When the bit is set, the foreground window
 * gets the event instead, so MediaSessionService.dispatchMediaKeyEvent()
 * never sees it. We mirror only that PASS_TO_USER path to CarAsistant and
 * never consume or modify the original event.
 */
public final class PolicyMediaKeyHooks {
    private static final String TAG = "[CarAsistant PolicyMediaKey]";
    private static final String SELF = "com.carassistant.v10";
    private static final String ACTION = "com.carassistant.v10.STEERING_RAW_MEDIA_KEY";
    private static final int ACTION_PASS_TO_USER = 0x00000001;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicLong EVENT_COUNT = new AtomicLong();
    // A single worker preserves DOWN/UP ordering and keeps Binder broadcasts
    // out of the input-policy callback while its input lock may be held.
    private static final ExecutorService BROADCAST_QUEUE =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "CarAsistant-PolicyKeyRelay");
                thread.setDaemon(true);
                return thread;
            });

    private PolicyMediaKeyHooks() { }

    public static void install(ClassLoader loader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> policyClass = XposedHelpers.findClass(
                    "com.android.server.policy.PhoneWindowManager", loader);
            int hooked = 0;
            for (Method method : policyClass.getDeclaredMethods()) {
                if (!"interceptKeyBeforeQueueing".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length != 2 || types[0] != KeyEvent.class
                        || types[1] != int.class || method.getReturnType() != int.class) {
                    continue;
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            KeyEvent event = (KeyEvent) param.args[0];
                            if (!isTarget(event)) return;

                            int result = ((Number) param.getResult()).intValue();
                            boolean passToUser = (result & ACTION_PASS_TO_USER) != 0;
                            long sequence = EVENT_COUNT.incrementAndGet();
                            XposedBridge.log(TAG + " POLICY#" + sequence
                                    + " key=" + KeyEvent.keyCodeToString(event.getKeyCode())
                                    + " action=" + actionName(event)
                                    + " repeat=" + event.getRepeatCount()
                                    + " deviceId=" + event.getDeviceId()
                                    + " source=0x" + Integer.toHexString(event.getSource())
                                    + " scanCode=" + event.getScanCode()
                                    + " policyFlags=0x" + Integer.toHexString(
                                            ((Number) param.args[1]).intValue())
                                    + " result=0x" + Integer.toHexString(result)
                                    + " route=" + (passToUser ? "PASS_TO_USER" : "MEDIA_SESSION"));

                            // This is the missing branch: the original event is passed
                            // to the foreground window, not MediaSessionService.
                            if (passToUser) relayWithoutBlocking(param.thisObject, event);
                        } catch (Throwable error) {
                            XposedBridge.log(TAG + " after-hook failed open: " + error);
                        }
                    }
                });
                hooked++;
            }

            if (hooked == 0) {
                INSTALLED.set(false);
                XposedBridge.log(TAG + " interceptKeyBeforeQueueing(KeyEvent,int) not found");
            } else {
                XposedBridge.log(TAG + " installed on PhoneWindowManager (" + hooked + ")");
            }
        } catch (Throwable error) {
            INSTALLED.set(false);
            XposedBridge.log(TAG + " install failed: " + error);
        }
    }

    private static boolean isTarget(KeyEvent event) {
        if (event == null) return false;
        int key = event.getKeyCode();
        return key == KeyEvent.KEYCODE_MEDIA_NEXT
                || key == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || key == KeyEvent.KEYCODE_MEDIA_REWIND;
    }

    private static String actionName(KeyEvent event) {
        return event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP";
    }

    private static void relayWithoutBlocking(Object policy, KeyEvent event) {
        final KeyEvent copy = new KeyEvent(event);
        final Context context;
        try {
            context = (Context) XposedHelpers.getObjectField(policy, "mContext");
        } catch (Throwable error) {
            XposedBridge.log(TAG + " cannot read PhoneWindowManager.mContext: " + error);
            return;
        }
        if (context == null) {
            XposedBridge.log(TAG + " PhoneWindowManager.mContext is null");
            return;
        }

        try {
            BROADCAST_QUEUE.execute(() -> {
                try {
                    Intent intent = new Intent(ACTION)
                            .setPackage(SELF)
                            .putExtra(Intent.EXTRA_KEY_EVENT, copy);
                    context.sendBroadcast(intent);
                    XposedBridge.log(TAG + " relayed PASS_TO_USER "
                            + KeyEvent.keyCodeToString(copy.getKeyCode()) + "/"
                            + actionName(copy) + " to CarAsistant observer");
                } catch (Throwable error) {
                    XposedBridge.log(TAG + " relay failed: " + error);
            });
        } catch (Throwable error) {
            XposedBridge.log(TAG + " relay queue rejected event: " + error);
        }
    }
}
