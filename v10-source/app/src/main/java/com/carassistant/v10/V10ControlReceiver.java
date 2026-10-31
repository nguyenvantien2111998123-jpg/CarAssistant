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
        if (V10ProjectionStandbyService.ACTION_CONNECTED.equals(action)) {
            Intent service = new Intent(context, V10ProjectionStandbyService.class)
                    .setAction(V10ProjectionStandbyService.ACTION_CONNECTED);
            try {
                context.startForegroundService(service);
            } catch (RuntimeException error) {
                Log.w("CarAssistantStandby", "Could not start projection standby service", error);
            }
            return;
        }
        if (V10ProjectionStandbyService.ACTION_DISCONNECTED.equals(action)) {
            Intent service = new Intent(context, V10ProjectionStandbyService.class)
                    .setAction(V10ProjectionStandbyService.ACTION_DISCONNECTED);
            try {
                context.startService(service);
            } catch (RuntimeException error) {
                context.stopService(service);
                context.getSharedPreferences("carassistant_media_resume", Context.MODE_PRIVATE)
                        .edit().putBoolean("projection_connected", false).apply();
                Log.w("CarAssistantStandby", "Disconnect notification failed", error);
            }
            return;
        }
        if ("com.carassistant.v10.STEERING_VOICE".equals(action)) {
            V10CarActivity activity = V10SessionStore.getCurrentActivity();
            if (activity != null) activity.handleSteeringVoice();
            else Log.w("CarAssistantSteering", "V10 activity is not active; voice request ignored");
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
