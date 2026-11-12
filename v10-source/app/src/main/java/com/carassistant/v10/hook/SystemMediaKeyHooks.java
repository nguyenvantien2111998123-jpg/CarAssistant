package com.carassistant.v10.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Routes hardware media keys directly to CarAsistant's active MediaSession.
 *
 * The hook is installed on MediaSessionService.SessionManagerImpl, the actual
 * Android 12 Binder implementation. It also prepares the microphone service
 * from the system process when the steering-wheel NEXT double press is complete.
 */
public final class SystemMediaKeyHooks {
    private static final String TAG = "[CarAsistant SystemMediaKey]";
    private static final String SELF = "com.carassistant.v10";
    private static final int NEXT = KeyEvent.KEYCODE_MEDIA_NEXT;
    private static final int PREVIOUS = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    private static final long DOUBLE_CLICK_MS = 350L;
    private static final long LONG_PRESS_MS = 500L;
    private static final Object VOICE_GESTURE_LOCK = new Object();
    private static long lastNextClickUp;
    private static boolean secondNextTapCandidate;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicLong RAW_EVENT_COUNT = new AtomicLong();

    private SystemMediaKeyHooks() { }

    public static void install(ClassLoader loader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> managerClass = XposedHelpers.findClass(
                    "com.android.server.media.MediaSessionService$SessionManagerImpl", loader);
            int hooked = 0;
            for (Method method : managerClass.getDeclaredMethods()) {
                if (!"dispatchMediaKeyEvent".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length != 4
                        || types[0] != String.class
                        || types[1] != boolean.class
                        || !KeyEvent.class.isAssignableFrom(types[2])
                        || types[3] != boolean.class
                        || method.getReturnType() != void.class) {
                    continue;
                }

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        KeyEvent event = findKeyEvent(param.args);
                        if (!isTargetKey(event)) return;

                        logRawEvent(param, event);
                        try {
                            Object service = getOuterService(param.thisObject);
                            if (service == null) return;

                            Context context = (Context) XposedHelpers.getObjectField(
                                    service, "mContext");
                            if (!hasMediaAccess(context)) return;

                            // The system process is the caller for this explicit FGS start.
                            // This avoids asking the app's background BroadcastReceiver to
                            // create a while-in-use microphone service.
                            maybePrepareVoiceService(context, event);

                            // Route the raw hardware event directly to the phone-side app.
                            // This path does not depend on an active/playing MediaSession.
                            Intent relay = new Intent(
                                    "com.carassistant.v10.STEERING_KEY_EVENT");
                            relay.setComponent(new ComponentName(
                                    SELF, SELF + ".V10ControlReceiver"));
                            relay.putExtra(Intent.EXTRA_KEY_EVENT, event);
                            context.sendBroadcast(relay);
                            // Prevent the same physical event from being delivered a second time
                            // through normal MediaSession routing.
                            param.setResult(null);
                        } catch (Throwable error) {
                            // Fail open: never disable the steering controls of other players.
                            XposedBridge.log(TAG + " route failed; allowing framework fallback: "
                                    + error);
                        }
                    }
                });
                hooked++;
            }

            if (hooked == 0) {
                INSTALLED.set(false);
                XposedBridge.log(TAG + " Android 12 dispatchMediaKeyEvent signature not found");
            } else {
                XposedBridge.log(TAG + " installed on SessionManagerImpl (" + hooked + ")");
            }
        } catch (Throwable error) {
            INSTALLED.set(false);
            XposedBridge.log(TAG + " install failed: " + error);
        }
    }

    /**
     * Mirrors the existing NEXT double-tap gesture (350 ms, with a 500 ms
     * long-press exclusion). The app-side handler still owns media actions;
     * this only starts the voice FGS before the app receives STEERING_VOICE.
     */
    private static void maybePrepareVoiceService(Context context, KeyEvent event) {
        if (context == null || event == null || event.getKeyCode() != NEXT) return;

        boolean prepare = false;
        synchronized (VOICE_GESTURE_LOCK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                long delta = event.getEventTime() - lastNextClickUp;
                secondNextTapCandidate = lastNextClickUp > 0L
                        && delta >= 0L && delta <= DOUBLE_CLICK_MS;
                if (secondNextTapCandidate) lastNextClickUp = 0L;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                long held = event.getEventTime() - event.getDownTime();
                if (held >= LONG_PRESS_MS) {
                    secondNextTapCandidate = false;
                    lastNextClickUp = 0L;
                } else if (secondNextTapCandidate) {
                    prepare = true;
                    secondNextTapCandidate = false;
                    lastNextClickUp = 0L;
                } else {
                    lastNextClickUp = event.getEventTime();
                }
            }
        }

        if (!prepare) return;
        try {
            Intent serviceIntent = new Intent();
            serviceIntent.setComponent(new ComponentName(
                    SELF, SELF + ".CarAsistantVoiceService"));
            serviceIntent.setAction("com.carassistant.v10.voice.PREPARE");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Throwable error) {
            // The steering event is still relayed; the app receiver can report
            // that voice is unavailable without breaking media-key handling.
            XposedBridge.log(TAG + " voice service preparation failed: " + error);
        }
    }

    private static KeyEvent findKeyEvent(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof KeyEvent) return (KeyEvent) arg;
        }
        return null;
    }

    private static boolean isTargetKey(KeyEvent event) {
        if (event == null) return false;
        int key = event.getKeyCode();
        return key == NEXT || key == PREVIOUS
                || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || key == KeyEvent.KEYCODE_MEDIA_REWIND;
    }

    private static Object getOuterService(Object managerImpl) {
        if (managerImpl == null) return null;
        for (Class<?> type = managerImpl.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getName().startsWith("this$")) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(managerImpl);
                    if (value != null && "com.android.server.media.MediaSessionService"
                            .equals(value.getClass().getName())) {
                        return value;
                    }
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    /**
     * Reads the current user's active records under MediaSessionService.mLock,
     * then returns the token belonging to the CarAsistant package.
     */
    @SuppressWarnings("unchecked")
    private static MediaSession.Token findCarAsistantSessionToken(Object service)
            throws Exception {
        Object lock = XposedHelpers.getObjectField(service, "mLock");
        Object currentUser = XposedHelpers.getObjectField(service, "mCurrentFullUserRecord");
        if (lock == null || currentUser == null) return null;

        int userId = XposedHelpers.getIntField(currentUser, "mFullUserId");
        Method getActive = findMethod(service.getClass(), "getActiveSessionsLocked",
                int.class);
        if (getActive == null) return null;

        List<?> records;
        synchronized (lock) {
            Object result = getActive.invoke(service, userId);
            if (!(result instanceof List)) return null;
            records = (List<?>) result;
            for (Object record : records) {
                if (record == null) continue;
                Object packageName = XposedHelpers.callMethod(record, "getPackageName");
                if (!SELF.equals(packageName)) continue;
                Object active = XposedHelpers.callMethod(record, "isActive");
                if (!(active instanceof Boolean) || !((Boolean) active)) continue;
                Object token = XposedHelpers.callMethod(record, "getSessionToken");
                if (token instanceof MediaSession.Token) {
                    return (MediaSession.Token) token;
                }
            }
        }
        return null;
    }

    private static boolean dispatchToSession(Object service, KeyEvent event,
                                             MediaSession.Token token) throws Exception {
        Method dispatch = findMethod(service.getClass(),
                "dispatchMediaKeyEventToSessionAsSystemService",
                String.class, KeyEvent.class, MediaSession.Token.class);
        if (dispatch == null) return false;
        Object result = dispatch.invoke(service, SELF, event, token);
        return Boolean.TRUE.equals(result);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!name.equals(method.getName())) continue;
                Class<?>[] actual = method.getParameterTypes();
                if (actual.length != parameters.length) continue;
                boolean matches = true;
                for (int i = 0; i < actual.length; i++) {
                    if (actual[i] != parameters[i]) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    private static boolean hasMediaAccess(Context context) {
        if (context == null) return false;
        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(), "enabled_notification_listeners");
            if (TextUtils.isEmpty(enabled)) return false;
            String expected = new ComponentName(
                    SELF, SELF + ".MediaSessionBridge").flattenToString();
            for (String item : enabled.split(":")) {
                ComponentName parsed = ComponentName.unflattenFromString(item);
                if (parsed != null && expected.equals(parsed.flattenToString())) return true;
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + " could not verify Notification Access: " + error);
        }
        return false;
    }

    private static void logRawEvent(XC_MethodHook.MethodHookParam param, KeyEvent event) {
        String callerPackage = "unknown";
        boolean asSystemService = false;
        if (param.args != null) {
            for (Object arg : param.args) {
                if (arg instanceof String && "unknown".equals(callerPackage)) {
                    callerPackage = (String) arg;
                } else if (arg instanceof Boolean) {
                    asSystemService = (Boolean) arg;
                }
            }
        }
        long sequence = RAW_EVENT_COUNT.incrementAndGet();
        XposedBridge.log(TAG + " RAW#" + sequence
                + " callerPkg=" + callerPackage
                + " asSystemService=" + asSystemService
                + " key=" + KeyEvent.keyCodeToString(event.getKeyCode())
                + " action=" + (event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP")
                + " repeat=" + event.getRepeatCount()
                + " deviceId=" + event.getDeviceId()
                + " source=0x" + Integer.toHexString(event.getSource())
                + " scanCode=" + event.getScanCode()
                + " flags=0x" + Integer.toHexString(event.getFlags())
                + " downTime=" + event.getDownTime()
                + " eventTime=" + event.getEventTime());
    }

    private static String describe(KeyEvent event) {
        return KeyEvent.keyCodeToString(event.getKeyCode()) + "/"
                + (event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP")
                + "/repeat=" + event.getRepeatCount();
    }
}
