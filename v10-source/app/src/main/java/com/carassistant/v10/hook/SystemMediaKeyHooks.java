package com.carassistant.v10.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.media.session.MediaSession;
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
 * Android 12 Binder implementation. Gesture recognition remains exclusively
 * inside SteeringWheelMediaKeyHandler in the app process.
 */
public final class SystemMediaKeyHooks {
    private static final String TAG = "[CarAsistant SystemMediaKey]";
    private static final String SELF = "com.carassistant.v10";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final int NEXT = KeyEvent.KEYCODE_MEDIA_NEXT;
    private static final int PREVIOUS = KeyEvent.KEYCODE_MEDIA_PREVIOUS;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicLong RAW_EVENT_COUNT = new AtomicLong();
    private static final GearheadGestureRouter GEARHEAD_GESTURES = new GearheadGestureRouter();

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
                            boolean fromGearhead = isGearheadCaller(param.args);
                            if (fromGearhead) {
                                // Warm the microphone foreground service before a possible
                                // double-NEXT gesture. Recognition itself starts only on double tap.
                                if (event.getKeyCode() == NEXT
                                        && event.getAction() == KeyEvent.ACTION_DOWN
                                        && event.getRepeatCount() == 0) {
                                    ensureVoiceServiceStartedBySystem(context);
                                }

                                // Gearhead KeyEvents are classified inside system_server.
                                // Do not bounce DOWN/UP through a short-lived app BroadcastReceiver:
                                // Android may reclaim that process before the 350 ms double-tap
                                // window or the 500 ms long-press timer completes.
                                if (GEARHEAD_GESTURES.dispatch(context, event)) {
                                    param.setResult(null);
                                    XposedBridge.log(TAG + " classified Gearhead "
                                            + describe(event) + " in system_server");
                                    return;
                                }
                            }
                            MediaSession.Token token = hasMediaAccess(context)
                                    ? findCarAsistantSessionToken(service) : null;

                            if (token == null) {
                                XposedBridge.log(TAG + " no active CarAsistant MediaSession; "
                                        + "allowing framework fallback");
                                return;
                            }

                            if (dispatchToSession(service, event, token)) {
                                // dispatchMediaKeyEvent is void on Android 12.
                                // Consume only after the framework confirms the target accepted it.
                                param.setResult(null);
                                XposedBridge.log(TAG + " routed " + describe(event)
                                        + " to CarAsistant MediaSession");
                            } else {
                                XposedBridge.log(TAG + " direct session dispatch returned false; "
                                        + "allowing framework fallback");
                            }
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

    private static boolean isGearheadCaller(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (GEARHEAD.equals(arg)) return true;
        }
        return false;
    }

    /**
     * system_server is the privileged initiator for this service start. Android 12
     * restricts background app-initiated FGS starts, especially microphone services;
     * do not attempt to solve that restriction from V10ControlReceiver.
     */
    private static void ensureVoiceServiceStartedBySystem(Context context) {
        if (context == null) return;
        try {
            if (context.getPackageManager().checkPermission(
                    "android.permission.RECORD_AUDIO", SELF)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            Intent service = new Intent()
                    .setClassName(SELF,
                            "com.carassistant.v10.CarAssistantVoiceService")
                    .setAction("com.carassistant.v10.voice.action.START");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + " system-initiated voice service warm start failed: "
                    + error);
        }
    }

    /**
     * Gearhead sends the physical steering-wheel KeyEvents into system_server.
     * Keep gesture timing here, where the input stream and its process lifetime
     * are stable, then send only completed semantic actions to the app.
     */
    private static final class GearheadGestureRouter {
        private static final long DOUBLE_CLICK_MS = 350L;
        private static final long LONG_PRESS_MS = 500L;
        private static final long SEEK_INTERVAL_MS = 1000L;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Object lock = new Object();
        private Context systemContext;
        private int pressedKey = KeyEvent.KEYCODE_UNKNOWN;
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

        boolean dispatch(Context context, KeyEvent event) {
            if (context == null || event == null || !isTargetKey(event)) return false;
            systemContext = context;
            final int key = normalizeKey(event.getKeyCode());
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() != 0) return true;
                synchronized (lock) {
                    if (pressedKey != KeyEvent.KEYCODE_UNKNOWN) return true;
                    long now = SystemClock.uptimeMillis();
                    secondNextTapCandidate = key == NEXT && nextClickPending
                            && now - lastNextClickUp <= DOUBLE_CLICK_MS;
                    if (secondNextTapCandidate) {
                        handler.removeCallbacks(pendingNextSingle);
                        nextClickPending = false;
                    }
                    pressedKey = key;
                    longPressStarted = false;
                    longPressRunnable = () -> startLongPress(key);
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                }
                return true;
            }

            if (event.getAction() != KeyEvent.ACTION_UP) return true;
            boolean wasLongPress;
            boolean invokeVoice = false;
            boolean invokePrevious = false;
            synchronized (lock) {
                if (pressedKey != key) return true;
                cancelLongPressLocked();
                wasLongPress = longPressStarted;
                pressedKey = KeyEvent.KEYCODE_UNKNOWN;
                longPressStarted = false;

                if (key == NEXT) {
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
            return true;
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
                        sendSeek(key == NEXT ? 1 : -1);
                    }
                };
                handler.postDelayed(seekRepeatRunnable, SEEK_INTERVAL_MS);
            }
            sendSeek(key == NEXT ? 1 : -1);
        }

        private void sendSeek(int direction) {
            sendAction("com.carassistant.v10.STEERING_MEDIA_SEEK", direction);
        }

        private void sendAction(String action, int direction) {
            Context context = systemContext;
            if (context == null) return;
            try {
                Intent intent = new Intent(action).setPackage(SELF);
                if (direction != 0) intent.putExtra("direction", direction);
                context.sendBroadcast(intent);
            } catch (Throwable error) {
                XposedBridge.log(TAG + " semantic steering action failed (" + action + "): " + error);
            }
        }

        private void cancelLongPressLocked() {
            if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
            if (seekRepeatRunnable != null) handler.removeCallbacks(seekRepeatRunnable);
            longPressRunnable = null;
            seekRepeatRunnable = null;
        }

        private static int normalizeKey(int keyCode) {
            return keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? NEXT
                    : keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ? PREVIOUS : keyCode;
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
