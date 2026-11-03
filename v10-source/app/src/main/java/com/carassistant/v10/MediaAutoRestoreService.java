package com.carassistant.v10;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

/**
 * Restores a media app after an abrupt phone reboot, but only when Android Auto
 * reconnects. The source app is launched on the phone default display; this service
 * never creates a VirtualDisplay and never opens the V10 car UI.
 */
public final class MediaAutoRestoreService extends Service {
    public static final String ACTION_RESTORE =
            "com.carassistant.v10.action.RESTORE_MEDIA_ON_AA_CONNECT";
    private static final String TAG = "CarAssistantMediaResume";
    private static final String CHANNEL = "carassistant_media_restore";
    private static final int NOTIFICATION_ID = 4038;
    private static final long RETRY_MS = 1000L;
    private static final long RETRY_WINDOW_MS = 600000L;
    private static final long LAUNCH_RETRY_INTERVAL_MS = 15000L;
    private static final int MAX_LAUNCH_ATTEMPTS = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RootShellSession rootShell = new RootShellSession();
    private long startedAt;
    private String expectedPackage;
    private int launchAttempts;
    private long lastLaunchElapsed;

    private final Runnable restoreCheckpoint = new Runnable() {
        @Override public void run() {
            if (TextUtils.isEmpty(expectedPackage)) { stopSelf(); return; }
            boolean autoplay = getSharedPreferences("media_autoplay", MODE_PRIVATE)
                    .getBoolean("enabled", true);
            if (MediaSessionBridge.applyCheckpoint(
                    MediaAutoRestoreService.this, expectedPackage, autoplay)) {
                clearRecoveryAssist();
                Log.i(TAG, "Restored source=" + expectedPackage + ", autoplay=" + autoplay);
                stopSelf();
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (launchAttempts < MAX_LAUNCH_ATTEMPTS
                    && (launchAttempts == 0
                    || now - lastLaunchElapsed >= LAUNCH_RETRY_INTERVAL_MS)) {
                launchAttempts++;
                lastLaunchElapsed = now;
                Log.i(TAG, "Source MediaSession not ready; launch attempt "
                        + launchAttempts + "/" + MAX_LAUNCH_ATTEMPTS
                        + " for " + expectedPackage);
                requestSourceLaunch();
            }
            if (now - startedAt < RETRY_WINDOW_MS) {
                handler.postDelayed(this, RETRY_MS);
            } else {
                Log.w(TAG, "Timed out waiting for source MediaSession: " + expectedPackage);
                stopSelf();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "CarAsistant media controller", NotificationManager.IMPORTANCE_LOW));
            startForeground(NOTIFICATION_ID, new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_aacast)
                    .setContentTitle("CarAsistant Media")
                    .setContentText("Đang mở lại ứng dụng media trên điện thoại")
                    .setOngoing(true).build());
        } else {
            startForeground(NOTIFICATION_ID, new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_aacast)
                    .setContentTitle("CarAsistant Media")
                    .setContentText("Đang mở lại ứng dụng media trên điện thoại")
                    .setOngoing(true).build());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(restoreCheckpoint);
        launchAttempts = 0;
        lastLaunchElapsed = 0L;
        expectedPackage = MediaSessionBridge.getCheckpointPackage(this);
        if (expectedPackage == null) expectedPackage = MediaSessionBridge.getLastKnownPackage(this);
        if (TextUtils.isEmpty(expectedPackage) || getPackageName().equals(expectedPackage)
                || !expectedPackage.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            Log.i(TAG, "No valid external media checkpoint to resume");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startedAt = SystemClock.uptimeMillis();
        String savedQuery = joinTitleArtist(MediaSessionBridge.getCheckpointTitle(this),
                MediaSessionBridge.getCheckpointArtist(this));
        getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                .putBoolean("pending_projection_restore", true)
                .putString("target_package", expectedPackage)
                .putString("query", savedQuery)
                .putString("phase", "await_projection")
                .putInt("target_display_id", -1)
                .putLong("started_at", System.currentTimeMillis())
                .putBoolean("mini_engine_active", false)
                .apply();
        Log.i(TAG, "Android Auto connected; waiting to restore saved task on V10 projection display: "
                + expectedPackage);
        handler.post(restoreCheckpoint);
        return START_NOT_STICKY;
    }

    /**
     * Do not launch the source app on display 0. After a power-loss reboot there is
     * no surviving Android task to move; V10 must recreate it only after its AA
     * projection surface is attached. This service records the pending restore and
     * waits for the matching MediaSession to appear from that projection task.
     */
    private void requestSourceLaunch() {
        String query = joinTitleArtist(MediaSessionBridge.getCheckpointTitle(this),
                MediaSessionBridge.getCheckpointArtist(this));
        getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                .putBoolean("pending_projection_restore", true)
                .putString("target_package", expectedPackage)
                .putString("query", query)
                .putString("phase", "await_projection")
                .putInt("target_display_id", -1)
                .putLong("started_at", System.currentTimeMillis())
                .putBoolean("mini_engine_active", false)
                .apply();
        Log.i(TAG, "Deferred source task launch until V10 projection display is ready; "
                + "phone display remains untouched: " + expectedPackage);
    }

    private void prepareAccessibilityRecovery() {
        String query = joinTitleArtist(MediaSessionBridge.getCheckpointTitle(this),
                MediaSessionBridge.getCheckpointArtist(this));
        if (TextUtils.isEmpty(query)) return;
        getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                .putString("target_package", expectedPackage)
                .putString("query", query)
                .putString("phase", "search")
                .putInt("target_display_id", 0)
                .putLong("started_at", System.currentTimeMillis())
                .putBoolean("mini_engine_active", false).apply();
    }

    private void clearRecoveryAssist() {
        getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                .remove("pending_projection_restore")
                .remove("target_package").remove("query").remove("phase")
                .remove("target_display_id").remove("started_at").remove("search_submitted_at").apply();
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

    @Override public void onDestroy() {
        handler.removeCallbacks(restoreCheckpoint);
        rootShell.destroy();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
