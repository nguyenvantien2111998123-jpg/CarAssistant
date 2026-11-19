package com.carassistant.v10;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class V10ControlReceiver
        extends BroadcastReceiver {

    public static final String ACTION =
            "com.carassistant.v10.CONTROL";
    public static final String EXTRA_COMMAND =
            "command";

    private static final String ACTION_STEERING_RAW_MEDIA_KEY =
            "com.carassistant.v10.STEERING_RAW_MEDIA_KEY";
    private static volatile SteeringWheelMediaKeyHandler rawKeyObserver;

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
