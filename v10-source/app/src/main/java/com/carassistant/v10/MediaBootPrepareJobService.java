package com.carassistant.v10;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.text.TextUtils;
import android.util.Log;

/**
 * Short post-boot process warm-up. This job deliberately does not launch an Activity,
 * start a media-playback foreground service, or launch the source player. Those actions
 * are deferred until the Gearhead hook observes a real Android Auto connection.
 */
public final class MediaBootPrepareJobService extends JobService {
    private static final String TAG = "CarAssistantMediaResume";

    @Override
    public boolean onStartJob(JobParameters params) {
        String source = MediaSessionBridge.getCheckpointPackage(this);
        if (TextUtils.isEmpty(source)) {
            source = MediaSessionBridge.getLastKnownPackage(this);
        }

        if (TextUtils.isEmpty(source) || getPackageName().equals(source)
                || !source.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            Log.i(TAG, "Post-boot preparation skipped; no valid external checkpoint");
            return false;
        }

        getSharedPreferences(MediaBootReceiver.PREFS, MODE_PRIVATE).edit()
                .putBoolean(MediaBootReceiver.KEY_BOOT_PENDING, true)
                .putBoolean(MediaBootReceiver.KEY_BOOT_PREPARE_READY, true)
                .putLong(MediaBootReceiver.KEY_BOOT_PREPARE_AT, System.currentTimeMillis())
                .commit();
        Log.i(TAG, "Post-boot process preparation completed for " + source
                + "; waiting for Android Auto connection");
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
