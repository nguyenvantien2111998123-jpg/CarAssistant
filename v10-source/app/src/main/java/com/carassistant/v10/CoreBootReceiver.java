package com.carassistant.v10;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Minimal boot entry point.
 *
 * It initializes only process-scoped Core bookkeeping. It deliberately does
 * not start microphone/media foreground services or try to launch an Activity
 * from the background. The process may be reclaimed after onReceive(); the
 * Android Auto host hook bootstraps Core again when its projection UI appears.
 */
public final class CoreBootReceiver extends BroadcastReceiver {
    private static final String TAG = "CarAsstBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        try {
            CarAssistantCore.startRecoveryMonitor(context.getApplicationContext(),
                    "CoreBootReceiver:" + action);
            Log.i(TAG, "Core bootstrap recorded for " + action
                    + "; no Activity or foreground service started");
        } catch (RuntimeException error) {
            Log.e(TAG, "Core boot bootstrap failed for " + action, error);
        }
    }
}
