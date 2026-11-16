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

    /** Bootstrap Core from Android Auto's bound projection service, never from phone UI. */
    public static void startRecoveryMonitor(Context context, String entryPoint) {
        CarAssistantCoreRuntime.start(context, entryPoint);
    }

    public static void reportCoreComponent(String component, boolean ready, String detail) {
        CarAssistantCoreRuntime.reportComponent(component, ready, detail);
    }

    public static void reportAndroidAutoConnection(boolean connected) {
        CarAssistantCoreRuntime.reportAndroidAutoConnection(connected);
    }

    public static String getCoreDiagnostics() {
        return CarAssistantCoreRuntime.getDiagnostics();
    }

    private CarAssistantCore() { }

    /** Create the process-independent command executor used by the voice service. */
    public static CarAssistantCommandExecutor createCommandExecutor(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        return new CarAssistantCommandExecutor(context.getApplicationContext());
    }

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
