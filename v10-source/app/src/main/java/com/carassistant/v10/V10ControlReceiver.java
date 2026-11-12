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

    @Override
    public void onReceive(
            Context context,
            Intent intent) {

        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        if ("com.carassistant.v10.STEERING_KEY_EVENT".equals(action)) {
            android.view.KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            MediaSessionBridge.dispatchSteeringKeyEvent(event);
            return;
        }
        if ("com.carassistant.v10.STEERING_VOICE".equals(action)) {
            Log.i("CarAssistantSteering", "Receiver got STEERING_VOICE");
            if (!CarAsistantVoiceService.toggleListening(context)) {
                Log.w("CarAssistantSteering", "Voice service unavailable; microphone permission or foreground-service start is required");
            }
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
