package com.carassistant.v10;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

import java.util.LinkedHashMap;
import java.util.Map;

public final class V10ControlReceiver
        extends BroadcastReceiver {

    public static final String ACTION =
            "com.carassistant.v10.CONTROL";
    public static final String EXTRA_COMMAND =
            "command";

    private static final String ACTION_STEERING_RAW_MEDIA_KEY =
            "com.carassistant.v10.STEERING_RAW_MEDIA_KEY";
    private static volatile SteeringWheelMediaKeyHandler rawKeyObserver;

    // Policy observes PASS_TO_USER before the foreground app receives the key.
    // If that app does not consume it, Android may later deliver the same event
    // through MediaSessionService. Suppress that duplicate physical event.
    private static final Object RAW_EVENT_LOCK = new Object();
    private static final LinkedHashMap<String, Long> RECENT_RAW_EVENTS =
            new LinkedHashMap<String, Long>(32, 0.75f, true);
    private static final long RAW_EVENT_DEDUPE_WINDOW_MS = 1500L;

    private static boolean isDuplicateRawEvent(Intent intent) {
        KeyEvent event;
        try {
            event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        } catch (RuntimeException error) {
            return false;
        }
        if (event == null) return false;

        String key = event.getDeviceId() + ":" + event.getKeyCode() + ":"
                + event.getAction() + ":" + event.getDownTime() + ":"
                + event.getEventTime() + ":" + event.getRepeatCount();
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (RAW_EVENT_LOCK) {
            java.util.Iterator<Map.Entry<String, Long>> iterator =
                    RECENT_RAW_EVENTS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Long> entry = iterator.next();
                if (now - entry.getValue() > RAW_EVENT_DEDUPE_WINDOW_MS) {
                    iterator.remove();
                }
            }
            if (RECENT_RAW_EVENTS.containsKey(key)) return true;
            RECENT_RAW_EVENTS.put(key, now);
            while (RECENT_RAW_EVENTS.size() > 64) {
                String eldest = RECENT_RAW_EVENTS.keySet().iterator().next();
                RECENT_RAW_EVENTS.remove(eldest);
            }
            return false;
        }
    }

    private static SteeringWheelMediaKeyHandler getRawKeyObserver(Context context) {
        SteeringWheelMediaKeyHandler current = rawKeyObserver;
        if (current != null) return current;
        synchronized (V10ControlReceiver.class) {
            if (rawKeyObserver == null) {
                Context app = context.getApplicationContext();
                rawKeyObserver = new SteeringWheelMediaKeyHandler(
                        new SteeringWheelMediaKeyHandler.Actions() {
                            // The observer must not duplicate normal framework media routing.
                            @Override public void onNext() { }
                            @Override public void onPrevious() { }
                            @Override public void onSeekForward() { }
                            @Override public void onSeekBackward() { }
                            @Override public void onVoiceAssistant() {
                                Intent voice = new Intent("com.carassistant.v10.STEERING_VOICE")
                                        .setPackage(app.getPackageName());
                                app.sendBroadcast(voice);
                            }
                        });
            }
            return rawKeyObserver;
        }
    }

    @Override
    public void onReceive(
            Context context,
            Intent intent) {

        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        if (ACTION_STEERING_RAW_MEDIA_KEY.equals(action)) {
            if (isDuplicateRawEvent(intent)) {
                Log.d("CarAssistantSteering", "Ignored duplicate raw media key");
                return;
            }
            Log.d("CarAssistantSteering", "Observed raw fallback media key");
            getRawKeyObserver(context).handle(intent);
            return;
        }
        if ("com.carassistant.v10.STEERING_VOICE".equals(action)) {
            Log.i("CarAssistantSteering", "Receiver got STEERING_VOICE");
            V10CarActivity activity = V10SessionStore.getCurrentActivity();
            if (activity != null) activity.handleSteeringVoice();
            else Log.w("CarAssistantSteering", "V10 activity is not active; voice request ignored");
            return;
        }
        if ("com.carassistant.v10.STEERING_MEDIA_NEXT".equals(action)) {
            Log.i("CarAssistantSteering", "Receiver got STEERING_MEDIA_NEXT");
            MediaSessionBridge.skipNext();
            return;
        }
        if ("com.carassistant.v10.STEERING_MEDIA_PREVIOUS".equals(action)) {
            Log.i("CarAssistantSteering", "Receiver got STEERING_MEDIA_PREVIOUS");
            MediaSessionBridge.skipPrevious();
            return;
        }
        if ("com.carassistant.v10.STEERING_MEDIA_SEEK".equals(action)) {
            int direction = intent.getIntExtra("direction", 0);
            Log.i("CarAssistantSteering", "Receiver got STEERING_MEDIA_SEEK direction=" + direction);
            if (direction != 0) MediaSessionBridge.seekBy(direction > 0 ? 15000L : -15000L);
            return;
        }

        if (!ACTION.equals(action)) {
            return;
        }

        String command =
                intent.getStringExtra(
                        EXTRA_COMMAND);

        if (command == null) {
            return;
        }

        command =
                command.trim()
                        .toLowerCase();

        V10CarActivity activity =
                V10SessionStore
                        .getCurrentActivity();

        if (activity == null) {
            return;
        }

        switch (command) {

            case "back":
                activity.handleControlBack();
                break;

            case "home":
                activity.handleControlHome();
                break;

            case "recents":
                activity.handleControlRecents();
                break;

            case "apps":
                activity.handleControlApps();
                break;

            default:
                break;
        }
    }
}
