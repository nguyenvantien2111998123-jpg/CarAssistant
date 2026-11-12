package com.carassistant.v10;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

public final class V10ControlReceiver
        extends BroadcastReceiver {

    private static volatile SteeringWheelMediaKeyHandler steeringKeyHandler;

    private static synchronized SteeringWheelMediaKeyHandler getSteeringKeyHandler(
            Context context) {
        if (steeringKeyHandler == null) {
            final Context appContext = context.getApplicationContext();
            steeringKeyHandler = new SteeringWheelMediaKeyHandler(
                    new SteeringWheelMediaKeyHandler.Actions() {
                        @Override public void onNext() {
                            MediaSessionBridge.skipNext();
                        }

                        @Override public void onPrevious() {
                            MediaSessionBridge.skipPrevious();
                        }

                        @Override public void onSeekForward() {
                            MediaSessionBridge.seekBy(15000L);
                        }

                        @Override public void onSeekBackward() {
                            MediaSessionBridge.seekBy(-15000L);
                        }

                        @Override public void onVoiceAssistant() {
                            Intent voice = new Intent("com.carassistant.v10.STEERING_VOICE")
                                    .setPackage(appContext.getPackageName());
                            appContext.sendBroadcast(voice);
                        }
                    });
        }
        return steeringKeyHandler;
    }

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
            KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            // Gesture recognition lives in this receiver, not in the optional
            // NotificationListener/MediaSession mirror. Android Auto can replace
            // its visible media card without disabling steering gestures.
            if (event != null) getSteeringKeyHandler(context).handle(event);
            return;
        }
        if ("com.carassistant.v10.STEERING_VOICE".equals(action)) {
            Log.i("CarAssistantSteering", "Receiver got STEERING_VOICE");
            // The System Framework hook has already prepared the microphone FGS
            // for this double press. Queue the toggle if Service.onCreate is still
            // completing; never start a microphone FGS from this background receiver.
            CarAsistantVoiceService.requestSteeringToggle(context);
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
