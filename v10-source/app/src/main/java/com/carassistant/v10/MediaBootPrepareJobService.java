package com.carassistant.v10;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Restores the saved media session immediately after Android reports BOOT_COMPLETED.
 * No Android Auto connection is required. The job launches the saved player using
 * the existing root capability, then waits for its MediaSession and reapplies position
 * plus autoplay. Accessibility search is armed when no working deep link is available.
 */
public final class MediaBootPrepareJobService extends JobService {
    private static final String TAG = "CarAssistantMediaResume";
    private static final long RESTORE_WINDOW_MS = 120000L;
    private static final long POLL_INTERVAL_MS = 1500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean launchStarted = new AtomicBoolean(false);
    private JobParameters runningParams;
    private String expectedPackage;
    private long startedAt;
    private boolean autoplay = true;

    private final Runnable checkpointPoll = new Runnable() {
        @Override public void run() {
            if (TextUtils.isEmpty(expectedPackage) || runningParams == null) return;
            if (MediaSessionBridge.applyCheckpoint(
                    MediaBootPrepareJobService.this, expectedPackage, autoplay)) {
                clearPendingState();
                Log.i(TAG, "Boot restore succeeded without Android Auto; source="
                        + expectedPackage + ", autoplay=" + autoplay);
                finishJob(false);
                return;
            }
            if (System.currentTimeMillis() - startedAt >= RESTORE_WINDOW_MS) {
                Log.w(TAG, "Boot restore timed out waiting for MediaSession: " + expectedPackage);
                finishJob(false);
                return;
            }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    public boolean onStartJob(JobParameters params) {
        runningParams = params;
        expectedPackage = MediaSessionBridge.getCheckpointPackage(this);
        if (TextUtils.isEmpty(expectedPackage)) {
            expectedPackage = MediaSessionBridge.getLastKnownPackage(this);
        }
        if (TextUtils.isEmpty(expectedPackage) || getPackageName().equals(expectedPackage)
                || !expectedPackage.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            Log.i(TAG, "Boot restore skipped; no valid external media checkpoint");
            runningParams = null;
            return false;
        }

        android.content.SharedPreferences prefs = getSharedPreferences(
                MediaBootReceiver.PREFS, MODE_PRIVATE);
        prefs.edit()
                .putBoolean(MediaBootReceiver.KEY_BOOT_PENDING, true)
                .putBoolean(MediaBootReceiver.KEY_BOOT_PREPARE_READY, true)
                .putLong(MediaBootReceiver.KEY_BOOT_PREPARE_AT, System.currentTimeMillis())
                .commit();
        autoplay = getSharedPreferences("media_autoplay", MODE_PRIVATE)
                .getBoolean("enabled", true);
        startedAt = System.currentTimeMillis();
        launchStarted.set(false);

        final String packageName = expectedPackage;
        final String mediaUri = MediaSessionBridge.getCheckpointMediaUri(this);
        final String title = MediaSessionBridge.getCheckpointTitle(this);
        final String artist = MediaSessionBridge.getCheckpointArtist(this);
        Log.i(TAG, "Boot restore started immediately; source=" + packageName
                + ", hasDeepLink=" + !TextUtils.isEmpty(mediaUri)
                + ", autoplay=" + autoplay);

        // Arm the search fallback before launching an app that has no usable deep link.
        if (TextUtils.isEmpty(mediaUri)) prepareAccessibilityRecovery(packageName, title, artist);

        RootShellSession.EXEC.execute(() -> launchSourceApp(packageName, mediaUri, title, artist));
        handler.removeCallbacks(checkpointPoll);
        handler.post(checkpointPoll);
        return true;
    }

    private void launchSourceApp(String packageName, String mediaUri, String title, String artist) {
        if (!launchStarted.compareAndSet(false, true)) return;
        RootShellSession shell = new RootShellSession();
        String pkg = shellQuote(packageName);
        if (!TextUtils.isEmpty(mediaUri)) {
            try {
                shell.run(15, "am start --display 0 -a android.intent.action.VIEW -d "
                        + shellQuote(mediaUri) + " -p " + pkg);
                Log.i(TAG, "Opened saved media deep link after boot: " + packageName);
                return;
            } catch (RuntimeException error) {
                Log.w(TAG, "Saved media deep link rejected; falling back to app search", error);
            }
        }

        prepareAccessibilityRecovery(packageName, title, artist);
        String query = joinTitleArtist(title, artist);
        if (!TextUtils.isEmpty(query)) {
            try {
                shell.run(15, "am start --display 0 -a android.intent.action.SEARCH"
                        + " --es query " + shellQuote(query) + " -p " + pkg);
                Log.i(TAG, "Opened source search after boot: " + packageName);
                return;
            } catch (RuntimeException error) {
                Log.i(TAG, "Source app has no compatible SEARCH activity; opening launcher", error);
            }
        }
        try {
            shell.run(15, "am start --display 0 -a android.intent.action.MAIN"
                    + " -c android.intent.category.LAUNCHER -p " + pkg);
            Log.i(TAG, "Launched saved media app after boot: " + packageName);
        } catch (Throwable error) {
            Log.e(TAG, "Root-assisted boot launch failed: " + packageName, error);
        } finally {
            shell.destroy();
        }
    }

    private void prepareAccessibilityRecovery(String packageName, String title, String artist) {
        String query = joinTitleArtist(title, artist);
        if (TextUtils.isEmpty(query)) return;
        getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                .putString("target_package", packageName)
                .putString("query", query)
                .putString("phase", "search")
                .putInt("target_display_id", 0)
                .putLong("started_at", System.currentTimeMillis())
                .putBoolean("mini_engine_active", false)
                .remove("search_submitted_at")
                .apply();
    }

    private void clearPendingState() {
        getSharedPreferences(MediaBootReceiver.PREFS, MODE_PRIVATE).edit()
                .putBoolean(MediaBootReceiver.KEY_BOOT_PENDING, false)
                .putBoolean(MediaBootReceiver.KEY_BOOT_PREPARE_READY, false)
                .commit();
        getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                .remove("target_package").remove("query").remove("phase")
                .remove("target_display_id").remove("started_at")
                .remove("search_submitted_at").apply();
    }

    private void finishJob(boolean needsReschedule) {
        handler.removeCallbacks(checkpointPoll);
        JobParameters params = runningParams;
        runningParams = null;
        if (params != null) jobFinished(params, needsReschedule);
    }

    private static String joinTitleArtist(String title, String artist) {
        if (TextUtils.isEmpty(title)) return "";
        if (TextUtils.isEmpty(artist) || title.toLowerCase(java.util.Locale.ROOT)
                .contains(artist.toLowerCase(java.util.Locale.ROOT))) return title;
        return title + " " + artist;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        handler.removeCallbacks(checkpointPoll);
        runningParams = null;
        return true;
    }
}
