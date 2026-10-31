package com.carassistant.v10;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

/** Headless media restore started by the Gearhead connection hook, without opening V10 UI. */
public final class MediaAutoRestoreService extends Service {
    public static final String ACTION_RESTORE = "com.carassistant.v10.action.RESTORE_MEDIA_ON_AA_CONNECT";
    private static final String TAG = "CarAssistantAutoRestore";
    private static final String CHANNEL = "carassistant_media_restore";
    private static final int NOTIFICATION_ID = 4038;
    private static final int DISPLAY_WIDTH = 1280, DISPLAY_HEIGHT = 720;
    private static final long RETRY_MS = 1000L, RETRY_WINDOW_MS = 25000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SurfaceTexture headlessTexture;
    private String restoringPackage;
    private long restoreStartedAt;
    private boolean displayPrepared;

    private final Runnable applyCheckpoint = new Runnable() {
        @Override public void run() {
            if (restoringPackage == null) return;
            boolean autoplay = getSharedPreferences("media_autoplay", MODE_PRIVATE).getBoolean("enabled", true);
            if (MediaSessionBridge.applyCheckpoint(MediaAutoRestoreService.this, restoringPackage, autoplay)) {
                Log.i(TAG, "Checkpoint applied after AA connection; autoplay=" + autoplay);
                return;
            }
            if (android.os.SystemClock.uptimeMillis() - restoreStartedAt < RETRY_WINDOW_MS)
                handler.postDelayed(this, RETRY_MS);
            else Log.w(TAG, "Media session did not reconnect in time for " + restoringPackage);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "CarAsistant media restore", NotificationManager.IMPORTANCE_LOW));
            startForeground(NOTIFICATION_ID, new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_aacast).setContentTitle("CarAsistant")
                    .setContentText("Đang duy trì phiên media trên Android Auto").setOngoing(true).build());
        } else {
            startForeground(NOTIFICATION_ID, new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_aacast).setContentTitle("CarAsistant")
                    .setContentText("Đang duy trì phiên media trên Android Auto").setOngoing(true).build());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_RESTORE.equals(intent.getAction())) prepareAndRestore();
        return START_STICKY;
    }

    private void prepareAndRestore() {
        String packageName = MediaSessionBridge.getCheckpointPackage(this);
        if (packageName == null) packageName = MediaSessionBridge.getLastKnownPackage(this);
        if (packageName == null || getPackageName().equals(packageName)) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null || launch.getComponent() == null) {
            Log.w(TAG, "Saved media app has no launcher activity: " + packageName); return;
        }
        V10DisplayController controller = V10DisplayController.get(this, 1);
        if (!controller.isReady()) {
            try {
                headlessTexture = new SurfaceTexture(0);
                headlessTexture.setDefaultBufferSize(DISPLAY_WIDTH, DISPLAY_HEIGHT);
                displayPrepared = controller.ensureDisplay(new Surface(headlessTexture), DISPLAY_WIDTH, DISPLAY_HEIGHT);
            } catch (Throwable error) { Log.e(TAG, "Could not create headless projection display", error); return; }
        } else displayPrepared = true;
        if (!displayPrepared) { Log.e(TAG, "Virtual display creation failed"); return; }
        restoringPackage = packageName;
        restoreStartedAt = android.os.SystemClock.uptimeMillis();
        ComponentName target = launch.getComponent();
        if (!controller.isLaunched() || !target.equals(controller.getTarget()))
            controller.launchMediaTaskOnDisplay(target);
        handler.removeCallbacks(applyCheckpoint);
        handler.postDelayed(applyCheckpoint, 1800L);
        Log.i(TAG, "AA connected: restored " + packageName + " on V10 display " + controller.getDisplayId());
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(applyCheckpoint);
        if (headlessTexture != null) { try { headlessTexture.release(); } catch (Throwable ignored) { } headlessTexture = null; }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
