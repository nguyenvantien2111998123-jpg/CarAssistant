package com.carassistant.v10;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

/**
 * Restores the checkpointed media task on V10 Display 2 as soon as Android Auto
 * reconnects. The headless ImageReader Surface exists before V10's CarActivity;
 * opening V10 later only replaces that Surface and adopts the already-running task.
 */
public final class MediaAutoRestoreService extends Service {
    public static final String ACTION_RESTORE =
            "com.carassistant.v10.action.RESTORE_MEDIA_ON_AA_CONNECT";
    public static final String ACTION_AA_DISCONNECTED =
            "com.carassistant.v10.action.AA_DISCONNECTED";
    private static final String TAG = "CarAssistantMediaResume";
    private static final String CHANNEL = "carassistant_media_restore";
    private static final int NOTIFICATION_ID = 4038;
    private static final long RETRY_MS = 1000L;
    private static final long RETRY_WINDOW_MS = 600000L;
    private static final long LAUNCH_RETRY_INTERVAL_MS = 15000L;
    private static final int MAX_LAUNCH_ATTEMPTS = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long startedAt;
    private String expectedPackage;
    private ComponentName expectedComponent;
    private int launchAttempts;
    private long lastLaunchElapsed;
    private boolean checkpointApplied;
    private boolean contentRestoreRequested;
    private long taskLaunchedAt;
    private long contentRestoreAt;

    private final Runnable disconnectMonitor = new Runnable() {
        @Override public void run() {
            boolean connected = getSharedPreferences("carassistant_media_resume", MODE_PRIVATE)
                    .getBoolean("aa_connected", false);
            if (!connected) {
                handler.removeCallbacks(restoreCheckpoint);
                HeadlessProjectionHost.releaseIfHeadless(MediaAutoRestoreService.this);
                stopForeground(true);
                stopSelf();
                Log.i(TAG, "AA notification disappeared; stopped headless restore service");
                return;
            }
            handler.postDelayed(this, 5000L);
        }
    };

    private final Runnable restoreCheckpoint = new Runnable() {
        @Override public void run() {
            if (TextUtils.isEmpty(expectedPackage) || expectedComponent == null) {
                Log.w(TAG, "No valid saved media component; headless restore cannot continue");
                return;
            }

            // Display 2 is V10 displayIndex=1. It is created by this foreground
            // service, not by V10CarActivity, so no user tap is needed after AA connects.
            if (!HeadlessProjectionHost.start(MediaAutoRestoreService.this)) {
                if (SystemClock.uptimeMillis() - startedAt < RETRY_WINDOW_MS) {
                    handler.postDelayed(this, RETRY_MS);
                } else {
                    Log.e(TAG, "Timed out creating headless V10 Display 2");
                }
                return;
            }

            V10DisplayController controller = V10DisplayController.get(
                    MediaAutoRestoreService.this, HeadlessProjectionHost.DISPLAY_INDEX);
            long now = SystemClock.uptimeMillis();
            if (!controller.isLaunched()
                    && launchAttempts < MAX_LAUNCH_ATTEMPTS
                    && (launchAttempts == 0
                    || now - lastLaunchElapsed >= LAUNCH_RETRY_INTERVAL_MS)) {
                requestSourceLaunch(controller);
            }
            if (controller.isLaunched()) {
                if (taskLaunchedAt == 0L) taskLaunchedAt = now;
                boolean exactItemReady = MediaSessionBridge.isCheckpointMediaReady(
                        MediaAutoRestoreService.this, expectedPackage);
                if (!exactItemReady && !contentRestoreRequested
                        && now - taskLaunchedAt >= 4000L) {
                    requestCheckpointContent(controller);
                } else if (!exactItemReady && contentRestoreRequested
                        && "checkpoint_uri".equals(getSharedPreferences(
                                "media_autoplay", MODE_PRIVATE).getString("phase", ""))
                        && now - contentRestoreAt >= 12000L) {
                    String fallbackQuery = joinTitleArtist(
                            MediaSessionBridge.getCheckpointTitle(MediaAutoRestoreService.this),
                            MediaSessionBridge.getCheckpointArtist(MediaAutoRestoreService.this));
                    if (!TextUtils.isEmpty(fallbackQuery)) {
                        getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                                .putString("phase", "search")
                                .putLong("started_at", System.currentTimeMillis())
                                .putInt("target_display_id", controller.getDisplayId())
                                .apply();
                        controller.launchSearchIntentOnDisplay(expectedPackage, fallbackQuery);
                        contentRestoreAt = now;
                        Log.i(TAG, "Checkpoint URI did not publish the saved item; starting title search");
                    }
                }
            }

            if (!checkpointApplied) {
                boolean autoplay = getSharedPreferences("media_autoplay", MODE_PRIVATE)
                        .getBoolean("enabled", true);
                if (MediaSessionBridge.applyCheckpoint(
                        MediaAutoRestoreService.this, expectedPackage, autoplay)) {
                    checkpointApplied = true;
                    // Keep the handoff marker until V10 opens. Its CarActivity must
                    // still know that Display 2 already owns this task and adopt it.
                    if (HeadlessProjectionHost.isHeadless()) {
                        getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                                .putString("phase", "headless_media_applied")
                                .putInt("target_display_id", controller.getDisplayId())
                                .apply();
                    } else {
                        // V10 has already adopted the running task; the handoff marker
                        // is no longer needed and must not trigger a second restore.
                        clearRecoveryAssist();
                    }
                    updateForegroundStatus("Đã khôi phục phiên media trên màn hình phụ");
                    Log.i(TAG, "Restored source=" + expectedPackage + ", autoplay=" + autoplay
                            + ", displayId=" + controller.getDisplayId());
                    return;
                }
            }

            if (SystemClock.uptimeMillis() - startedAt < RETRY_WINDOW_MS) {
                handler.postDelayed(this, RETRY_MS);
            } else {
                // Keep this foreground service alive while AA is connected: it owns
                // the ImageReader producer and therefore the headless VirtualDisplay.
                Log.w(TAG, "MediaSession did not match checkpoint within retry window: "
                        + expectedPackage + "; keeping Display 2 alive until AA disconnect");
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification(
                "Đang chuẩn bị khôi phục media trên màn hình phụ"));
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
            handler.removeCallbacks(restoreCheckpoint);
            HeadlessProjectionHost.releaseIfHeadless(this);
            stopForeground(true);
            stopSelf(startId);
            Log.i(TAG, "Android Auto disconnected; released headless projection resources");
            return START_NOT_STICKY;
        }

        handler.removeCallbacks(restoreCheckpoint);
        handler.removeCallbacks(disconnectMonitor);
        handler.postDelayed(disconnectMonitor, 5000L);
        launchAttempts = 0;
        lastLaunchElapsed = 0L;
        checkpointApplied = false;
        contentRestoreRequested = false;
        taskLaunchedAt = 0L;
        contentRestoreAt = 0L;
        expectedPackage = MediaSessionBridge.getCheckpointPackage(this);
        if (expectedPackage == null) expectedPackage = MediaSessionBridge.getLastKnownPackage(this);
        expectedComponent = MediaSessionBridge.getCheckpointTaskComponent(this);
        if (TextUtils.isEmpty(expectedPackage) || expectedComponent == null
                || !expectedPackage.equals(expectedComponent.getPackageName())) {
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(expectedPackage);
                expectedComponent = launch == null ? null : launch.getComponent();
            } catch (RuntimeException ignored) {
                expectedComponent = null;
            }
        }

        if (TextUtils.isEmpty(expectedPackage) || getPackageName().equals(expectedPackage)
                || !expectedPackage.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
                || expectedComponent == null) {
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
                .putString("phase", "headless_display2")
                .putInt("target_display_id", -1)
                .putLong("started_at", System.currentTimeMillis())
                .putBoolean("mini_engine_active", false)
                .putBoolean("content_restore_requested", false)
                .apply();

        updateForegroundStatus("Đang khởi chạy " + expectedPackage + " trên Display 2");
        Log.i(TAG, "AA reconnect accepted; creating headless Display 2 for "
                + expectedComponent.flattenToString());
        handler.post(restoreCheckpoint);
        return START_STICKY;
    }

    private void requestSourceLaunch(V10DisplayController controller) {
        if (controller == null || !controller.isReady() || expectedComponent == null) return;
        launchAttempts++;
        lastLaunchElapsed = SystemClock.uptimeMillis();
        getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                .putString("phase", "launching_on_display2")
                .putInt("target_display_id", controller.getDisplayId())
                .apply();
        Log.i(TAG, "Launching saved component on V10 Display 2, attempt "
                + launchAttempts + "/" + MAX_LAUNCH_ATTEMPTS + ": "
                + expectedComponent.flattenToString());
        controller.launchMediaTaskOnDisplay(expectedComponent);
    }

    private void requestCheckpointContent(V10DisplayController controller) {
        if (contentRestoreRequested || controller == null || !controller.isReady()) return;
        contentRestoreRequested = true;
        contentRestoreAt = SystemClock.uptimeMillis();
        String uri = MediaSessionBridge.getCheckpointMediaUri(this);
        String query = joinTitleArtist(MediaSessionBridge.getCheckpointTitle(this),
                MediaSessionBridge.getCheckpointArtist(this));
        android.content.SharedPreferences.Editor editor =
                getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                        .putBoolean("content_restore_requested", true)
                        .putString("target_package", expectedPackage)
                        .putString("query", query)
                        .putInt("target_display_id", controller.getDisplayId())
                        .putLong("started_at", System.currentTimeMillis())
                        .putBoolean("mini_engine_active", false);
        if (!TextUtils.isEmpty(uri)) {
            editor.putString("phase", "checkpoint_uri").apply();
            controller.launchCheckpointUriOnDisplay(expectedPackage, uri);
            Log.i(TAG, "Opening saved media URI on Display 2 for " + expectedPackage);
        } else if (!TextUtils.isEmpty(query)) {
            editor.putString("phase", "search").apply();
            controller.launchSearchIntentOnDisplay(expectedPackage, query);
            Log.i(TAG, "Searching saved media title on Display 2 for " + expectedPackage);
        } else {
            editor.putString("phase", "await_media_session").apply();
            Log.w(TAG, "No saved media URI or title; waiting for source app's own resume");
        }
    }

    private void clearRecoveryAssist() {
        getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                .remove("pending_projection_restore")
                .remove("target_package").remove("query").remove("phase")
                .remove("target_display_id").remove("started_at")
                .remove("search_submitted_at").remove("content_restore_requested").apply();
    }

    private static String joinTitleArtist(String title, String artist) {
        if (TextUtils.isEmpty(title)) return "";
        if (TextUtils.isEmpty(artist) || title.toLowerCase(java.util.Locale.ROOT)
                .contains(artist.toLowerCase(java.util.Locale.ROOT))) return title;
        return title + " " + artist;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(restoreCheckpoint);
        handler.removeCallbacks(disconnectMonitor);
        // If the service is stopped unexpectedly while the reader still owns the
        // Surface, release it. Once V10 takes over, releaseIfHeadless is a no-op.
        HeadlessProjectionHost.releaseIfHeadless(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
