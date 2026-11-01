package com.carassistant.v10;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

/**
 * Records the last media checkpoint after a full reboot and schedules a short,
 * persisted JobScheduler pass to initialize the app process without opening UI.
 * Actual media restoration remains gated on Android Auto reconnect.
 */
public final class MediaBootReceiver extends BroadcastReceiver {
    private static final String TAG = "CarAssistantMediaResume";
    static final String PREFS = "carassistant_media_resume";
    static final String KEY_BOOT_PENDING = "boot_restore_pending";
    static final String KEY_BOOT_RECEIVED_AT = "boot_restore_received_at";
    static final String KEY_BOOT_PREPARE_READY = "boot_prepare_ready";
    static final String KEY_BOOT_PREPARE_AT = "boot_prepare_completed_at";
    static final int PREPARE_JOB_ID = 0xCA41;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        String savedPackage = MediaSessionBridge.getCheckpointPackage(context);
        if (TextUtils.isEmpty(savedPackage)) {
            savedPackage = MediaSessionBridge.getLastKnownPackage(context);
        }
        if (TextUtils.isEmpty(savedPackage)
                || context.getPackageName().equals(savedPackage)
                || !savedPackage.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            Log.i(TAG, "Boot completed; no valid external media checkpoint to prepare");
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(KEY_BOOT_PENDING, true)
                .putBoolean(KEY_BOOT_PREPARE_READY, false)
                .putLong(KEY_BOOT_RECEIVED_AT, System.currentTimeMillis())
                .commit();

        // A persisted job gives Android a second, scheduler-managed opportunity to
        // start the CarAsistant process after boot. It performs no UI or media launch.
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) {
            ComponentName component = new ComponentName(context, MediaBootPrepareJobService.class);
            JobInfo job = new JobInfo.Builder(PREPARE_JOB_ID, component)
                    .setPersisted(true)
                    .setMinimumLatency(2500L)
                    .setOverrideDeadline(15000L)
                    .build();
            int result = scheduler.schedule(job);
            Log.i(TAG, "Boot recovery queued for " + savedPackage
                    + "; prepareJobScheduleResult=" + result);
        } else {
            Log.w(TAG, "JobScheduler unavailable; boot recovery remains pending");
        }
    }
}
