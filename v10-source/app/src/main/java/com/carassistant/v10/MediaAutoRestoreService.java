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
 * Background-only media checkpoint recovery.
 *
 * Android Auto's connected notification is the sole trigger. This service never
 * launches a source Activity, creates a projection display, or drives Accessibility.
 * It waits for the source app's real MediaSession and restores only a verified item.
 */
public final class MediaAutoRestoreService extends Service {
    public static final String ACTION_RESTORE =
            "com.carassistant.v10.action.RESTORE_MEDIA_ON_AA_CONNECT";
    public static final String ACTION_AA_DISCONNECTED =
            "com.carassistant.v10.action.AA_DISCONNECTED";

    private static final String TAG = "CarAssistantMediaResume";
    private static final String CHANNEL = "carassistant_media_restore";
    private static final int NOTIFICATION_ID = 4038;
    private static final long INITIAL_SETTLE_MS = 3500L;
    private static final long VERIFY_RETRY_MS = 2000L;
    private static final long VERIFY_WINDOW_MS = 120000L;

    private static volatile MediaAutoRestoreService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String expectedPackage;
    private long verifyStartedAt;
    private boolean completed;

    private final Runnable disconnectMonitor = new Runnable() {
        @Override public void run() {
            boolean connected = getSharedPreferences("carassistant_media_resume", MODE_PRIVATE)
                    .getBoolean("aa_connected", false);
            if (!connected) {
                handler.removeCallbacks(restoreAttempt);
                stopForeground(true);
                stopSelf();
                Log.i(TAG, "AA connected notification removed; background recovery stopped");
                return;
            }
            handler.postDelayed(this, 5000L);
        }
    };

    private final Runnable restoreAttempt = new Runnable() {
        @Override public void run() {
            if (completed || TextUtils.isEmpty(expectedPackage)) return;
            if (!getSharedPreferences("carassistant_media_resume", MODE_PRIVATE)
                    .getBoolean("aa_connected", false)) {
                Log.i(TAG, "AA is no longer connected; refusing media recovery");
                return;
            }

            if (!MediaSessionBridge.isCheckpointMediaReady(
                    MediaAutoRestoreService.this, expectedPackage)) {
                Log.i(TAG, "WAITING_FOR_SESSION: no exact checkpoint MediaSession yet; source="
                        + expectedPackage);
                // No UI launch and no fast polling. MediaSession callbacks wake this service
                // when a session or its metadata becomes available.
                return;
            }

            boolean autoplay = getSharedPreferences("media_autoplay", MODE_PRIVATE)
                    .getBoolean("enabled", true);
            if (MediaSessionBridge.applyCheckpoint(
                    MediaAutoRestoreService.this, expectedPackage, autoplay)) {
                completed = true;
                getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                        .putString("phase", "verified_background_restore")
                        .putBoolean("content_restore_requested", false)
                        .remove("pending_projection_restore")
                        .remove("target_display_id")
                        .apply();
                updateForegroundStatus("Đã xác minh khôi phục media trong nền");
                Log.i(TAG, "VERIFIED_BACKGROUND_RESTORE source=" + expectedPackage
                        + ", requestedState=" + (autoplay ? "PLAY" : "PAUSE"));
                handler.removeCallbacks(restoreAttempt);
                return;
            }

            // applyCheckpoint returns false until the player reports both the target
            // position and requested PLAY/PAUSE state. Retry only while a matching
            // session exists; session appearance itself is event-driven.
            if (verifyStartedAt == 0L) verifyStartedAt = SystemClock.elapsedRealtime();
            if (SystemClock.elapsedRealtime() - verifyStartedAt < VERIFY_WINDOW_MS) {
                handler.postDelayed(this, VERIFY_RETRY_MS);
            } else {
                Log.w(TAG, "Verification window expired; checkpoint retained, not reported restored");
                updateForegroundStatus("Chưa xác minh được phiên media; giữ checkpoint");
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification(
                "Đang chờ phiên media để khôi phục trong nền"));
    }

    /** Called by MediaSessionBridge when active sessions or source metadata change. */
    public static void notifyMediaSessionsChanged() {
        MediaAutoRestoreService service = instance;
        if (service != null) {
            service.handler.post(service.restoreAttempt);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "CarAsistant media controller", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification buildNotification(String message) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_aacast)
                    .setContentTitle("CarAsistant Media")
                    .setContentText(message)
                    .setOngoing(true)
                    .build();
        }
        return new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_aacast)
                .setContentTitle("CarAsistant Media")
                .setContentText(message)
                .setOngoing(true)
                .build();
    }

    private void updateForegroundStatus(String message) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(message));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RESTORE : intent.getAction();
        if (ACTION_AA_DISCONNECTED.equals(action)) {
            getSharedPreferences("carassistant_media_resume", MODE_PRIVATE).edit()
                    .putBoolean("aa_connected", false).apply();
            handler.removeCallbacks(restoreAttempt);
            handler.removeCallbacks(disconnectMonitor);
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        handler.removeCallbacks(restoreAttempt);
        handler.removeCallbacks(disconnectMonitor);
        completed = false;
        verifyStartedAt = 0L;
        expectedPackage = MediaSessionBridge.getCheckpointPackage(this);
        if (TextUtils.isEmpty(expectedPackage)) {
            expectedPackage = MediaSessionBridge.getLastKnownPackage(this);
        }

        if (TextUtils.isEmpty(expectedPackage) || getPackageName().equals(expectedPackage)
                || !expectedPackage.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            Log.w(TAG, "No valid external media checkpoint package; recovery not started");
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                .putString("phase", "waiting_for_media_session")
                .putBoolean("pending_projection_restore", false)
                .putBoolean("content_restore_requested", false)
                .remove("target_package")
                .remove("query")
                .remove("target_display_id")
                .remove("search_submitted_at")
                .apply();

        updateForegroundStatus("Đang chờ MediaSession của " + expectedPackage);
        handler.postDelayed(restoreAttempt, INITIAL_SETTLE_MS);
        handler.postDelayed(disconnectMonitor, 5000L);
        Log.i(TAG, "AA reconnect accepted; background MediaSession recovery only, source="
                + expectedPackage + ", no Activity/Display2/Accessibility launch");
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(restoreAttempt);
        handler.removeCallbacks(disconnectMonitor);
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
