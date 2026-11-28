package com.carassistant.v10;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Deterministic handoff after media recovery:
 * 1) send the phone to Home through the already-authorized root shell;
 * 2) wait exactly one second;
 * 3) launch WYN in its own receiver/process flow.
 */
public final class MediaRestoreCompleteReceiver extends BroadcastReceiver {
    public static final String ACTION =
            "com.carassistant.v10.ACTION_MEDIA_RECOVERY_FINISHED";
    private static final String TAG = "CarAssistantMediaResume";
    private static final long WYN_AFTER_HOME_MS = 1000L;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        final String result = intent.getStringExtra("restore_result");
        final PendingResult pending = goAsync();

        // A dedicated shell thread avoids waiting behind unrelated root commands.
        new Thread(() -> {
            RootShellSession shell = new RootShellSession();
            boolean homeAccepted = false;
            try {
                String output = "";
                try {
                    output = shell.run(5, "input keyevent 3");
                    homeAccepted = true;
                    Log.i(TAG, "MEDIA_HOME_SENT immediately; output=" + output);
                } catch (Throwable homeError) {
                    Log.e(TAG, "MEDIA_HOME root command failed; WYN will still start", homeError);
                }

                try {
                    Thread.sleep(WYN_AFTER_HOME_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }

                Intent startWyn = new Intent("com.carassistant.v10.ACTION_AUTO_BACKGROUND_WYN")
                        .setComponent(new ComponentName(context, WynAutoStartReceiver.class))
                        .putExtra("restore_result", result)
                        .putExtra("home_accepted", homeAccepted);
                context.sendBroadcast(startWyn);
                pending.setResultCode(Activity.RESULT_OK);
                Log.i(TAG, "WYN_HANDOFF_SENT delayAfterHomeMs=" + WYN_AFTER_HOME_MS
                        + "; homeAccepted=" + homeAccepted + "; restoreResult=" + result);
            } catch (Throwable error) {
                pending.setResultCode(Activity.RESULT_CANCELED);
                Log.e(TAG, "Could not hand off to WYN after media recovery", error);
            } finally {
                shell.destroy();
                pending.finish();
            }
        }, "CarAsistant-MediaRecovery-Home").start();
    }
}
