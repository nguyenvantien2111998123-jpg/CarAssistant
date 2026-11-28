package com.carassistant.v10;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/** Starts WYN and arms one monotonic 30-second Home deadline at launch acceptance. */
public final class WynAutoStartReceiver extends BroadcastReceiver {
    private static final String ACTION = "com.carassistant.v10.ACTION_AUTO_BACKGROUND_WYN";
    private static final String TAG = "CarAsistantWYN";
    private static final long HOME_DELAY_MS = 30000L;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        context.getSharedPreferences("wyn_auto_start", Context.MODE_PRIVATE).edit()
                .putBoolean("background_after_load", false)
                .remove("requested_at")
                .remove("home_at")
                .remove("launch_accepted_elapsed")
                .remove("home_deadline_elapsed")
                .commit();

        final PendingResult pending = goAsync();
        new Thread(() -> {
            RootShellSession shell = new RootShellSession();
            try {
                long commandStartedAt = SystemClock.elapsedRealtime();
                String output = shell.run(8,
                        "am start --user 0 --display 0 -n 'com.wynauto.wyn/.MainActivity'");
                // am start has returned successfully: this is the single source of
                // truth for the 30-second countdown, independent of Flutter readiness.
                long acceptedElapsed = SystemClock.elapsedRealtime();
                long acceptedWall = System.currentTimeMillis();
                long deadlineElapsed = acceptedElapsed + HOME_DELAY_MS;
                context.getSharedPreferences("wyn_auto_start", Context.MODE_PRIVATE).edit()
                        .putBoolean("background_after_load", true)
                        .putLong("requested_at", acceptedWall)
                        .putLong("home_at", acceptedWall + HOME_DELAY_MS)
                        .putLong("launch_accepted_elapsed", acceptedElapsed)
                        .putLong("home_deadline_elapsed", deadlineElapsed)
                        .commit();
                WynPopupAccessibilityService.onWynLaunchAccepted();
                pending.setResultCode(Activity.RESULT_OK);
                Log.i(TAG, "WYN_START_ACCEPTED display=0; shellDurationMs="
                        + (acceptedElapsed - commandStartedAt)
                        + "; homeDeadlineInMs=" + HOME_DELAY_MS
                        + "; restoreResult=" + intent.getStringExtra("restore_result")
                        + "; homeBeforeWyn=" + intent.getBooleanExtra("home_accepted", false)
                        + "; output=" + output);
            } catch (Throwable error) {
                pending.setResultCode(Activity.RESULT_CANCELED);
                Log.e(TAG, "WYN root launch failed; no 30-second timer armed", error);
            } finally {
                shell.destroy();
                pending.finish();
            }
        }, "CarAsistant-WYN-Launch").start();
    }
}
