package com.carassistant.v10.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaSessionManager;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Captures AVRCP media keys inside the Bluetooth process, before the event is
 * handed to the system media-session router.
 *
 * Android Bluetooth AVRCP implementations dispatch passthrough keys through
 * MediaSessionManager (legacy) or AudioManager (newer implementations). This
 * hook is intentionally installed in com.android.bluetooth, not system_server,
 * so the key is still observable when no media session exists.
 */
public final class BluetoothAvrcpInputHooks {
    private static final String TAG = "[CarAsistant Bluetooth AVRCP]";
    private static final String SELF = "com.carassistant.v10";
    private static final String ACTION = "com.carassistant.v10.STEERING_RAW_KEY";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicInteger HOOK_COUNT = new AtomicInteger();

    private BluetoothAvrcpInputHooks() { }

    public static void install(ClassLoader loader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        hookMediaSessionManager(loader);
        hookAudioManager(loader);

        int count = HOOK_COUNT.get();
        if (count == 0) {
            INSTALLED.set(false);
            XposedBridge.log(TAG + " no compatible Bluetooth media-key dispatch method found");
        } else {
            XposedBridge.log(TAG + " installed dispatch hooks=" + count);
        }
    }

    private static void hookMediaSessionManager(ClassLoader loader) {
        try {
            Class<?> type = XposedHelpers.findClass(
                    "android.media.session.MediaSessionManager", loader);
            for (Method method : type.getDeclaredMethods()) {
                if (!"dispatchMediaKeyEvent".equals(method.getName())
                        || method.getReturnType() != void.class) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length < 1 || p.length > 2 || p[0] != KeyEvent.class
                        || (p.length == 2 && p[1] != boolean.class)) continue;
                hook(method, "MediaSessionManager");
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + " MediaSessionManager hook unavailable: " + error);
        }
    }

    private static void hookAudioManager(ClassLoader loader) {
        try {
            Class<?> type = XposedHelpers.findClass("android.media.AudioManager", loader);
            for (Method method : type.getDeclaredMethods()) {
                if (!"dispatchMediaKeyEvent".equals(method.getName())
                        || method.getReturnType() != void.class) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 1 || p[0] != KeyEvent.class) continue;
                hook(method, "AudioManager");
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + " AudioManager hook unavailable: " + error);
        }
    }

    private static void hook(Method method, String owner) {
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0
                        || !(param.args[0] instanceof KeyEvent)) return;
                KeyEvent event = (KeyEvent) param.args[0];
                if (!isSteeringKey(event)) return;

                Context context = findContext(param.thisObject);
                if (context == null) {
                    XposedBridge.log(TAG + " " + owner
                            + " context unavailable; keeping original AVRCP route");
                    return;
                }

                try {
                    Intent forwarded = new Intent(ACTION)
                            .setComponent(new ComponentName(SELF,
                                    SELF + ".V10ControlReceiver"))
                            .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(event))
                            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                                    | Intent.FLAG_RECEIVER_FOREGROUND);
                    context.sendBroadcast(forwarded);

                    // Consume at the Bluetooth boundary. This prevents the same
                    // DOWN/UP pair being handled again by system_server hooks.
                    param.setResult(null);
                    XposedBridge.log(TAG + " captured " + describe(event)
                            + " at " + owner + " before MediaSession routing");
                } catch (Throwable error) {
                    // Fail open: never swallow a steering key if forwarding fails.
                    XposedBridge.log(TAG + " forward failed; original route retained: " + error);
                }
            }
        });
        HOOK_COUNT.incrementAndGet();
    }

    private static Context findContext(Object manager) {
        if (manager == null) return null;
        try {
            Object value = XposedHelpers.getObjectField(manager, "mContext");
            if (value instanceof Context) return (Context) value;
        } catch (Throwable ignored) { }
        for (Class<?> type = manager.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!Context.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(manager);
                    if (value instanceof Context) return (Context) value;
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static boolean isSteeringKey(KeyEvent event) {
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
