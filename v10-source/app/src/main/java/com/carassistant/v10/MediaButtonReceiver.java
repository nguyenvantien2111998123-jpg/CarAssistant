package com.carassistant.v10;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Manifest endpoint for ACTION_MEDIA_BUTTON fallback delivery.
 * All gestures are delegated to the existing shared steering classifier.
 */
public final class MediaButtonReceiver extends BroadcastReceiver {
    private static final String TAG = "CarAssistantMediaButton";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return;
        }
        KeyEvent event;
        try {
            event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not read media button KeyEvent", error);
            return;
        }
        if (event == null) {
            Log.w(TAG, "ACTION_MEDIA_BUTTON arrived without EXTRA_KEY_EVENT");
            return;
        }
        boolean handled = SteeringWheelInputDispatcher.dispatch(context, intent);
        Log.i(TAG, "Media button fallback "
                + KeyEvent.keyCodeToString(event.getKeyCode())
                + " action=" + event.getAction() + " handled=" + handled);
    }
}
