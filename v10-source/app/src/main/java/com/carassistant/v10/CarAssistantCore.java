package com.carassistant.v10;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Process-independent entry point for CarAsistant Core services.
 *
 * Activities and system/LSPosed receivers send commands here instead of
 * owning microphone-service lifecycle details themselves.
 */
public final class CarAssistantCore {
    private static final String TAG = "CarAssistantCore";

    private CarAssistantCore() { }

    /** Start/warm the voice foreground service from an allowed foreground context. */
    public static boolean ensureVoiceService(Context context) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return dispatchVoiceService(app, CarAssistantVoiceService.ACTION_START, true);
    }

    /** Toggle an already-warmed voice service (for dashboard and steering input). */
    public static boolean toggleVoice(Context context) {
        return dispatchVoiceService(context, CarAssistantVoiceService.ACTION_TOGGLE, false);
    }

    /** Cancel the active voice session without coupling it to an Activity. */
    public static boolean cancelVoice(Context context) {
        return dispatchVoiceService(context, CarAssistantVoiceService.ACTION_CANCEL, false);
    }

    private static boolean dispatchVoiceService(Context context, String action,
                                                boolean foregroundStart) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        Intent command = new Intent(app, CarAssistantVoiceService.class).setAction(action);
        try {
            if (foregroundStart && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(command);
            } else {
                // Toggle/cancel are delivered to the already-running service.
                // If it is absent, Android's background-start restrictions are
                // intentionally allowed to reject this request.
                app.startService(command);
            }
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to dispatch voice service action: " + action, error);
            return false;
        }
    }
}
