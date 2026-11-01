package com.carassistant.v10;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

/**
 * Marks the last durable media checkpoint for recovery after a complete device reboot.
 * It deliberately does not launch the media app here: playback is restored only after
 * the Gearhead hook confirms that Android Auto has connected again.
 */
public final class MediaBootReceiver extends BroadcastReceiver {
    private static final String TAG = "CarAssistantMediaResume";
    static final String PREFS = "carassistant_media_resume";
    static final String KEY_BOOT_PENDING = "boot_restore_pending";
    static final String KEY_BOOT_RECEIVED_AT = "boot_restore_received_at";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        String savedPackage = MediaSessionBridge.getCheckpointPackage(context);
        if (TextUtils.isEmpty(savedPackage)) {
            savedPackage = MediaSessionBridge.getLastKnownPackage(context);
        }
        if (TextUtils.isEmpty(savedPackage)
                || context.getPackageName().equals(savedPackage)) {
            Log.i(TAG, "Boot completed; no external media checkpoint to restore");
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(KEY_BOOT_PENDING, true)
                .putLong(KEY_BOOT_RECEIVED_AT, System.currentTimeMillis())
                .commit();
        Log.i(TAG, "Boot completed; preserved recovery request for " + savedPackage
                + ". Waiting for Android Auto connection before launching media.");
    }
}
