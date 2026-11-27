package com.carassistant.v10;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts WYN on phone display 0 and begins its fixed 30-second Home timer after launch is accepted. */
public final class WynAutoStartReceiver extends BroadcastReceiver {
    private static final String ACTION = "com.carassistant.v10.ACTION_AUTO_BACKGROUND_WYN";
    private static final String TAG = "CarAsistantWYN";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        // Do not arm the timer until am start has accepted WYN below.
        context.getSharedPreferences("wyn_auto_start", Context.MODE_PRIVATE).edit()
                .putBoolean("background_after_load", false)
                .remove("requested_at")
                .remove("home_at").commit();
        final PendingResult pending = goAsync();
        RootShellSession.EXEC.execute(() -> {
            RootShellSession shell = new RootShellSession();
            try {
                String output = shell.run(12,
                        "am start --user 0 --display 0 -n 'com.wynauto.wyn/.MainActivity'");
                long launchedAt = System.currentTimeMillis();
                context.getSharedPreferences("wyn_auto_start", Context.MODE_PRIVATE).edit()
                        .putBoolean("background_after_load", true)
                        .putLong("requested_at", launchedAt)
                        .putLong("home_at", launchedAt + 30000L).commit();
                WynPopupAccessibilityService.onWynLaunchAccepted();
                pending.setResultCode(Activity.RESULT_OK);
                Log.i(TAG, "WYN launched independently after media recovery backgrounded its source; restoreResult="
                        + intent.getStringExtra("restore_result")
                        + "; auto-background armed; homeDeadlineMs=30000; output=" + output);
            } catch (Throwable error) {
                pending.setResultCode(Activity.RESULT_CANCELED);
                Log.e(TAG, "WYN root launch failed; auto-background remains armed for retry", error);
            } finally {
                shell.destroy();
                pending.finish();
            }
        });
    }
}