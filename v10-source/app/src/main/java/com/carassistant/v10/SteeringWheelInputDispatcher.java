package com.carassistant.v10;

import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/**
 * Process-wide steering gesture dispatcher.
 *
 * The same classifier is used for MediaSession callbacks and for raw key events
 * forwarded by the system_server Gearhead input gateway. It deliberately does
 * not depend on a live MediaSession.
 */
public final class SteeringWheelInputDispatcher {
    public static final String ACTION_RAW_KEY =
            "com.carassistant.v10.STEERING_RAW_KEY";
    public static final String ACTION_STEERING_VOICE =
            "com.carassistant.v10.STEERING_VOICE";

    private static volatile SteeringWheelMediaKeyHandler handler;
    private static volatile Context applicationContext;

    private SteeringWheelInputDispatcher() { }

    public static boolean dispatch(Context context, Intent intent) {
        if (context == null || intent == null) return false;
        ensureHandler(context);
        SteeringWheelMediaKeyHandler current = handler;
        return current != null && current.handle(intent);
    }

    private static void ensureHandler(Context context) {
        if (handler != null) return;
        synchronized (SteeringWheelInputDispatcher.class) {
            if (handler != null) return;
            applicationContext = context.getApplicationContext();
            handler = new SteeringWheelMediaKeyHandler(
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
                            Context app = applicationContext;
                            if (app == null) return;
                            Intent voice = new Intent(ACTION_STEERING_VOICE)
                                    .setPackage(app.getPackageName());
                            app.sendBroadcast(voice);
                        }
                    });
        }
    }
}
