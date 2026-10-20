package com.carassistant.v10;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class V10ControlReceiver
        extends BroadcastReceiver {

    public static final String ACTION =
            "com.carassistant.v10.CONTROL";
    public static final String ACTION_STEERING_VOICE =
            "com.carassistant.v10.STEERING_VOICE";
    public static final String ACTION_STEERING_MEDIA_SEEK =
            "com.carassistant.v10.STEERING_MEDIA_SEEK";
    public static final String ACTION_STEERING_MEDIA_OPEN =
            "com.carassistant.v10.STEERING_MEDIA_OPEN";
    public static final String EXTRA_MEDIA_PACKAGE =
            "com.carassistant.v10.EXTRA_MEDIA_PACKAGE";
    private static final String MEDIA_OPEN_PREFS = "media_open";
    private static final String PENDING_MEDIA_PACKAGE = "pending_media_package";
    public static final String ACTION_STEERING_MEDIA_NEXT =
            "com.carassistant.v10.STEERING_MEDIA_NEXT";
    public static final String ACTION_STEERING_MEDIA_PREVIOUS =
            "com.carassistant.v10.STEERING_MEDIA_PREVIOUS";

    public static final String EXTRA_COMMAND =
            "command";

    @Override
    public void onReceive(
            Context context,
            Intent intent) {

        if (intent == null) {
            return;
        }

        if (ACTION_STEERING_VOICE.equals(intent.getAction())) {
            V10CarActivity voiceActivity = V10SessionStore.getCurrentActivity();
            if (voiceActivity != null) {
                voiceActivity.handleSteeringVoiceCommand();
            } else {
                V10SessionStore.requestVoiceOnNextActivity();
            }
            return;
        }

        if (ACTION_STEERING_MEDIA_NEXT.equals(intent.getAction())) {
            MediaSessionBridge.skipNext();
            return;
        }

        if (ACTION_STEERING_MEDIA_PREVIOUS.equals(intent.getAction())) {
            MediaSessionBridge.skipPrevious();
            return;
        }

        if (ACTION_STEERING_MEDIA_SEEK.equals(intent.getAction())) {
            int direction = intent.getIntExtra("direction", 0);
            if (direction != 0) MediaSessionBridge.seekBy(direction > 0 ? 15000L : -15000L);
            return;
        }

        if (ACTION_STEERING_MEDIA_OPEN.equals(intent.getAction())) {
            String requestedPackage = intent.getStringExtra(EXTRA_MEDIA_PACKAGE);
            V10CarActivity mediaActivity = V10SessionStore.getCurrentActivity();
            if (mediaActivity != null) {
                mediaActivity.handleOpenActiveMediaOnProjection(requestedPackage);
            } else if (requestedPackage != null && !requestedPackage.trim().isEmpty()) {
                context.getSharedPreferences(MEDIA_OPEN_PREFS, Context.MODE_PRIVATE)
                        .edit().putString(PENDING_MEDIA_PACKAGE, requestedPackage).apply();
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
