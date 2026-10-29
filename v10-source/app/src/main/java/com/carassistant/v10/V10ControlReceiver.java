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

        if ("com.carassistant.v10.FLOATING_MEDIA_CONTROL".equals(intent.getAction())) {
            String command = intent.getStringExtra("command");
            if (command == null) return;
            switch (command) {
                case "play_pause": MediaSessionBridge.togglePlayPause(); break;
                case "next": MediaSessionBridge.skipNext(); break;
                case "previous": MediaSessionBridge.skipPrevious(); break;
                case "seek_back": MediaSessionBridge.seekBy(-15000L); break;
                case "seek_forward": MediaSessionBridge.seekBy(15000L); break;
                case "seek_to": MediaSessionBridge.seekTo(intent.getLongExtra("position", 0L)); break;
                default: break;
            }
            return;
        }

        if ("com.carassistant.v10.STEERING_MEDIA_OPEN".equals(intent.getAction())) {
            V10CarActivity mediaActivity = V10SessionStore.getCurrentActivity();
            if (mediaActivity != null) {
                Log.i("CarAssistantMediaProxy", "Media metadata tap received; routing to active V10 projection");
                mediaActivity.handleShowCarAsistantOnProjection();
            } else {
                // Do not launch the phone launcher/settings as a substitute for the car UI.
                // Preserve the request so the dashboard is restored if Gearhead recreates V10CarActivity.
                V10SessionStore.requestShowDashboard();
                Log.w("CarAssistantMediaProxy",
                        "Media metadata tap received but V10CarActivity is not active; dashboard request queued");
            }
            return;
        }

        if (!ACTION.equals(
                intent.getAction())) {
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
