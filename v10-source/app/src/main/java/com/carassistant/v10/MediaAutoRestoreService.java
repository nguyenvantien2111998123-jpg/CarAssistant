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
import android.util.Log;

/**
 * Android Auto connection coordinator for the CarAsistant Media controller.
 *
 * It never launches a third-party activity and never creates a VirtualDisplay.
 * It restores the saved position through the source app's existing MediaSession,
 * while CarAsistant's own MediaSession remains the media surface shown by Android Auto.
 */
public final class MediaAutoRestoreService extends Service {
    public static final String ACTION_RESTORE =
            "com.carassistant.v10.action.RESTORE_MEDIA_ON_AA_CONNECT";
    private static final String TAG = "CarAssistantMediaResume";
    private static final String CHANNEL = "carassistant_media_restore";
    private static final int NOTIFICATION_ID = 4038;
    private static final long RETRY_MS = 1000L;
    private static final long RETRY_WINDOW_MS = 25000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long startedAt;
    private String expectedPackage;

    private final Runnable restoreCheckpoint = new Runnable() {
        @Override public void run() {
            if (expectedPackage == null) {
                stopSelf();
                return;
            }
            boolean autoplay = getSharedPreferences("media_autoplay", MODE_PRIVATE)
                    .getBoolean("enabled", true);
            if (MediaSessionBridge.applyCheckpoint(
                    MediaAutoRestoreService.this, expectedPackage, autoplay)) {
                Log.i(TAG, "CarAsistant Media resumed saved source=" + expectedPackage
                        + ", autoplay=" + autoplay);
                stopSelf();
                return;
            }
            if (SystemClock.uptimeMillis() - startedAt < RETRY_WINDOW_MS) {
                handler.postDelayed(this, RETRY_MS);
            } else {
                Log.w(TAG, "Saved MediaSession unavailable on AA connection: " + expectedPackage);
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
                    .setContentText("Đang khôi phục nội dung đã lưu trên Android Auto")
                    .setOngoing(true).build());
        } else {
            startForeground(NOTIFICATION_ID, new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_aacast)
                    .setContentTitle("CarAsistant Media")
                    .setContentText("Đang khôi phục nội dung đã lưu trên Android Auto")
                    .setOngoing(true).build());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(restoreCheckpoint);
        expectedPackage = MediaSessionBridge.getCheckpointPackage(this);
        if (expectedPackage == null) expectedPackage = MediaSessionBridge.getLastKnownPackage(this);
        if (expectedPackage == null || getPackageName().equals(expectedPackage)) {
            Log.i(TAG, "No external media checkpoint to resume");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startedAt = SystemClock.uptimeMillis();
        Log.i(TAG, "Android Auto connected; waiting for saved source session " + expectedPackage);
        handler.post(restoreCheckpoint);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(restoreCheckpoint);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
