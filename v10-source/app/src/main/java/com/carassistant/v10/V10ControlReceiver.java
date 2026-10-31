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
        if ("com.carassistant.v10.STEERING_VOICE".equals(action)) {
            V10CarActivity activity = V10SessionStore.getCurrentActivity();
            if (activity != null) {
                activity.handleSteeringVoice();
            } else {
                context.getSharedPreferences("media_autoplay", Context.MODE_PRIVATE)
                        .edit().putBoolean("voice_pending", true).apply();
                Intent open = new Intent("com.carassistant.v10.OPEN_CAR_APP")
                        .setPackage("com.google.android.projection.gearhead");
                try {
                    context.sendBroadcast(open);
                    Log.i("CarAssistantSteering", "Queued voice request and asked Gearhead to open CarAsistant");
                } catch (RuntimeException error) {
                    Log.w("CarAssistantSteering", "Could not open CarAsistant from media voice action", error);
                }
            }
            return;
        }
        if ("com.carassistant.v10.STEERING_MEDIA_NEXT".equals(action)) {
            MediaSessionBridge.skipNext();
            return;
        }
        if ("com.carassistant.v10.STEERING_MEDIA_PREVIOUS".equals(action)) {
            MediaSessionBridge.skipPrevious();
            return;
        }
        if ("com.carassistant.v10.STEERING_MEDIA_SEEK".equals(action)) {
            int direction = intent.getIntExtra("direction", 0);
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
