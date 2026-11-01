package com.carassistant.v10;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

/**
 * Device boot trigger. USB and Android Auto triggers are handled independently.
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
        enqueueRestore(context, "device boot");
    }

    static boolean enqueueRestore(Context context, String reason) {
        String savedPackage = MediaSessionBridge.getCheckpointPackage(context);
        if (TextUtils.isEmpty(savedPackage)) {
            savedPackage = MediaSessionBridge.getLastKnownPackage(context);
        }
        if (TextUtils.isEmpty(savedPackage)
                || context.getPackageName().equals(savedPackage)
                || !savedPackage.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            Log.i(TAG, "Restore trigger ignored; no valid external media checkpoint. source=" + reason);
            return false;
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_BOOT_PENDING, true)
                .putBoolean(KEY_BOOT_PREPARE_READY, false)
                .putLong(KEY_BOOT_RECEIVED_AT, System.currentTimeMillis())
                .commit();

        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            Log.w(TAG, "JobScheduler unavailable; restore remains pending. source=" + reason);
            return false;
        }
        ComponentName component = new ComponentName(context, MediaBootPrepareJobService.class);
        JobInfo job = new JobInfo.Builder(PREPARE_JOB_ID, component)
                .setPersisted(true)
                .setMinimumLatency(0L)
                .setOverrideDeadline(5000L)
                .build();
        int result = scheduler.schedule(job);
        Log.i(TAG, "Media restore queued; source=" + reason + ", package=" + savedPackage
                + ", scheduleResult=" + result);
        return result == JobScheduler.RESULT_SUCCESS;
    }
}
