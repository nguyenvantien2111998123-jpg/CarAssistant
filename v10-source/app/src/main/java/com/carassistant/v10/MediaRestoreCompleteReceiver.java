package com.carassistant.v10;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Handles the boundary between media recovery and the independent WYN startup
 * flow. MediaAutoRestoreService reports only that recovery has ended; this
 * receiver backgrounds the phone-side player and then signals WYN startup.
 */
public final class MediaRestoreCompleteReceiver extends BroadcastReceiver {
    public static final String ACTION =
            "com.carassistant.v10.ACTION_MEDIA_RECOVERY_FINISHED";
    private static final String TAG = "CarAssistantMediaResume";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        final String result = intent.getStringExtra("restore_result");
        final PendingResult pending = goAsync();
        RootShellSession.EXEC.execute(() -> {
            RootShellSession shell = new RootShellSession();
            try {
                String output = shell.run(8, "input keyevent 3; sleep 0.35");
                Intent startWyn = new Intent("com.carassistant.v10.ACTION_AUTO_BACKGROUND_WYN")
                        .setComponent(new ComponentName(context, WynAutoStartReceiver.class))
                        .putExtra("restore_result", result);
                context.sendBroadcast(startWyn);
                pending.setResultCode(Activity.RESULT_OK);
                Log.i(TAG, "Media app sent to phone background; independent WYN flow signalled; "
                        + "restoreResult=" + result + "; output=" + output);
            } catch (Throwable error) {
                pending.setResultCode(Activity.RESULT_CANCELED);
                Log.e(TAG, "Could not background phone media app after recovery", error);
                // Do not start WYN over a media app that may still be foreground.
            } finally {
                shell.destroy();
                pending.finish();
            }
        });
    }
}
