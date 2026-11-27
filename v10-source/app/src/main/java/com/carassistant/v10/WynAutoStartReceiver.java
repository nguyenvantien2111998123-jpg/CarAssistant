package com.carassistant.v10;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Arms WYN auto-background and starts its Home Activity on phone display 0 using root. */
public final class WynAutoStartReceiver extends BroadcastReceiver {
    private static final String ACTION = "com.carassistant.v10.ACTION_AUTO_BACKGROUND_WYN";
    private static final String TAG = "CarAsistantWYN";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        context.getSharedPreferences("wyn_auto_start", Context.MODE_PRIVATE).edit()
                .putBoolean("background_after_load", true)
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
                        .putLong("requested_at", launchedAt)
                        .putLong("home_at", launchedAt + 30000L).commit();
                pending.setResultCode(Activity.RESULT_OK);
                Log.i(TAG, "WYN launched independently after media recovery backgrounded its source; restoreResult="
                        + intent.getStringExtra("restore_result")
                        + "; auto-background armed; output=" + output);
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