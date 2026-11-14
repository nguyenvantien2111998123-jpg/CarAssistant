package com.carassistant.v10;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

/**
 * Phone-display media recovery.
 *
 * After Android Auto's connected notification remains stable, launch the saved
 * source app on the phone's default display. The phone AccessibilityService
 * searches/selects the saved item and the MediaSession checkpoint is applied
 * only after the source reports the matching item. No V10 Display 2 or
 * HeadlessProjectionHost is created by this recovery path.
 */
public final class MediaAutoRestoreService extends Service {
    public static final String ACTION_RESTORE =
            "com.carassistant.v10.action.RESTORE_MEDIA_ON_AA_CONNECT";
    public static final String ACTION_AA_DISCONNECTED =
            "com.carassistant.v10.action.AA_DISCONNECTED";

    private static final String TAG = "CarAssistantMediaResume";
    private static final String CHANNEL = "carassistant_media_restore";
    private static final int NOTIFICATION_ID = 4038;
    private static final long INITIAL_SETTLE_MS = 10000L;
    private static final long AA_CONNECTION_POLL_MS = 1000L;
    private static final long SESSION_RETRY_MS = 1500L;
    private static final long VERIFY_WINDOW_MS = 120000L;
    private static final int MAX_LAUNCH_ATTEMPTS = 3;

    private static volatile MediaAutoRestoreService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RootShellSession launchShell = new RootShellSession();

    private String expectedPackage;
    private ComponentName expectedComponent;
    private long verifyStartedAt;
    private long lastLaunchAt;
    private int launchAttempts;
    private boolean launchInFlight;
    private boolean launchAccepted;
    private boolean completed;
    private boolean connectionStabilized;

    private final Runnable disconnectMonitor = new Runnable() {
        @Override public void run() {
            boolean connected = getSharedPreferences("carassistant_media_resume", MODE_PRIVATE)
                    .getBoolean("aa_connected", false);
            if (!connected) {
                handler.removeCallbacks(restoreAttempt);
                handler.removeCallbacks(connectionStabilizer);
                HeadlessProjectionHost.releaseIfHeadless(MediaAutoRestoreService.this);
                MediaSessionBridge.notifyAndroidAutoDisconnected();
                stopForeground(true);
                stopSelf();
                Log.i(TAG, "AA connected notification removed; phone-side recovery stopped");
                return;
            }
            handler.postDelayed(this, AA_CONNECTION_POLL_MS);
        }
    };

    private final Runnable connectionStabilizer = new Runnable() {
        @Override public void run() {
            boolean connected = getSharedPreferences("carassistant_media_resume", MODE_PRIVATE)
                    .getBoolean("aa_connected", false);
            if (!connected) {
                Log.i(TAG, "AA notification did not remain present for stabilization window");
                HeadlessProjectionHost.releaseIfHeadless(MediaAutoRestoreService.this);
                MediaSessionBridge.notifyAndroidAutoDisconnected();
                stopForeground(true);
                stopSelf();
                return;
            }
            connectionStabilized = true;
            boolean displayReady = HeadlessProjectionHost.start(MediaAutoRestoreService.this);
            Log.i(TAG, "AA connection stable for " + INITIAL_SETTLE_MS
                    + " ms; headless Display 2 standby ready=" + displayReady
                    + "; phone-side media recovery may begin");
            handler.post(restoreAttempt);
        }
    };

    private final Runnable restoreAttempt = new Runnable() {
        @Override public void run() {
            if (completed || !connectionStabilized || TextUtils.isEmpty(expectedPackage)) return;
            if (!getSharedPreferences("carassistant_media_resume", MODE_PRIVATE)
                    .getBoolean("aa_connected", false)) {
                Log.i(TAG, "AA is no longer connected; refusing media recovery");
                return;
            }

            if (!launchAccepted) {
                if (!launchInFlight) launchSourceAppOnPhone();
                return;
            }

            if (MediaSessionBridge.isCheckpointMediaReady(
                    MediaAutoRestoreService.this, expectedPackage)) {
                boolean autoplay = getSharedPreferences("media_autoplay", MODE_PRIVATE)
                        .getBoolean("enabled", true);
                if (MediaSessionBridge.applyCheckpoint(
                        MediaAutoRestoreService.this, expectedPackage, autoplay)) {
                    completed = true;
                    MediaSessionBridge.notifyMediaRestoreFinished();
                    getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                            .putString("phase", "verified_phone_background_restore")
                            .putBoolean("content_restore_requested", false)
                            .putBoolean("pending_projection_restore", false)
                            .remove("target_package")
                            .remove("query")
                            .remove("target_display_id")
                            .remove("started_at")
                            .remove("search_submitted_at")
                            .remove("youtube_history_stage")
                            .apply();

                    boolean sentToBackground =
                            MediaPlaybackAccessibilityService.requestBackgroundSourceApp(
                                    expectedPackage);
                    updateForegroundStatus(sentToBackground
                            ? "Đã khôi phục media và đưa ứng dụng về nền"
                            : "Đã khôi phục media; chưa xác nhận ẩn giao diện nguồn");
                    Log.i(TAG, "VERIFIED_PHONE_RESTORE source=" + expectedPackage
                            + ", requestedState=" + (autoplay ? "PLAY" : "PAUSE")
                            + ", backgroundRequested=" + sentToBackground);
                    handler.removeCallbacks(restoreAttempt);
                    return;
                }
            }

            if (verifyStartedAt == 0L) verifyStartedAt = SystemClock.elapsedRealtime();
            if (SystemClock.elapsedRealtime() - verifyStartedAt < VERIFY_WINDOW_MS) {
                handler.postDelayed(this, SESSION_RETRY_MS);
            } else {
                updateForegroundStatus("Chưa xác minh được media; giữ checkpoint");
                Log.w(TAG, "Phone-side restore verification expired; checkpoint retained");
                completed = true;
                MediaSessionBridge.notifyMediaRestoreFinished();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification(
                "Đang chờ Android Auto ổn định"));
    }

    /** Called by MediaSessionBridge when active sessions or source metadata change. */
    public static void notifyMediaSessionsChanged() {
        MediaAutoRestoreService service = instance;
        if (service != null) service.handler.post(service.restoreAttempt);
    }

    /** True while a reconnect restore is still waiting for its final verification result. */
    public static boolean isRestoreInProgress() {
        MediaAutoRestoreService service = instance;
        return service != null && !service.completed;
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

    private void launchSourceAppOnPhone() {
        if (launchAttempts >= MAX_LAUNCH_ATTEMPTS) {
            updateForegroundStatus("Không thể khởi chạy ứng dụng media nguồn");
            Log.e(TAG, "Phone source launch exhausted retries for " + expectedPackage);
            completed = true;
            MediaSessionBridge.notifyMediaRestoreFinished();
            return;
        }

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(expectedPackage);
        expectedComponent = launchIntent == null ? null : launchIntent.getComponent();
        if (expectedComponent == null) {
            expectedComponent = MediaSessionBridge.getCheckpointTaskComponent(this);
        }
        if (expectedComponent == null
                || !expectedPackage.equals(expectedComponent.getPackageName())) {
            updateForegroundStatus("Không tìm thấy màn hình khởi chạy ứng dụng media");
            Log.e(TAG, "No launchable source Activity for " + expectedPackage);
            completed = true;
            MediaSessionBridge.notifyMediaRestoreFinished();
            return;
        }

        launchAttempts++;
        lastLaunchAt = SystemClock.elapsedRealtime();
        launchInFlight = true;

        // The source app is about to be brought back to the phone for Accessibility.
        // Invalidate only V10's cached Display 2 task marker so the next media-card
        // tap re-checks/moves the real phone task instead of assuming it is still there.
        V10DisplayController.get(this, HeadlessProjectionHost.DISPLAY_INDEX).markNotLaunched();

        String query = joinTitleArtist(MediaSessionBridge.getCheckpointTitle(this),
                MediaSessionBridge.getCheckpointArtist(this));
        getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                .putString("target_package", expectedPackage)
                .putString("query", query)
                .putString("phase", TextUtils.isEmpty(query) ? "await_session" : "search")
                .putInt("target_display_id", 0)
                .putLong("started_at", System.currentTimeMillis())
                .putLong("search_submitted_at", 0L)
                .putInt("youtube_history_stage", 0)
                .putBoolean("mini_engine_active", false)
                .putBoolean("content_restore_requested", true)
                .putBoolean("pending_projection_restore", false)
                .apply();

        updateForegroundStatus("Đang mở " + expectedPackage + " trên màn hình điện thoại");
        final ComponentName component = expectedComponent;
        final int userId = android.os.Process.myUid() / 100000;
        RootShellSession.EXEC.execute(() -> {
            boolean accepted = false;
            String output = "";
            try {
                String command = "am start --user " + userId + " --display 0 -n '"
                        + component.flattenToString() + "'";
                output = launchShell.run(12, command);
                accepted = true;
            } catch (RuntimeException error) {
                Log.w(TAG, "Root launch on phone display failed for " + expectedPackage, error);
            }
            final boolean launchResult = accepted;
            final String launchOutput = output;
            handler.post(() -> {
                launchInFlight = false;
                launchAccepted = launchResult;
                if (launchResult) {
                    Log.i(TAG, "Source app launched on phone display 0: "
                            + component.flattenToString() + " output=" + launchOutput);
                    handler.postDelayed(restoreAttempt, SESSION_RETRY_MS);
                } else if (launchAttempts < MAX_LAUNCH_ATTEMPTS) {
                    long delay = Math.max(1000L,
                            5000L - (SystemClock.elapsedRealtime() - lastLaunchAt));
                    handler.postDelayed(restoreAttempt, delay);
                } else {
                    updateForegroundStatus("Không thể mở ứng dụng nguồn; giữ checkpoint");
                    completed = true;
                    MediaSessionBridge.notifyMediaRestoreFinished();
                }
            });
        });
    }

    private static String joinTitleArtist(String title, String artist) {
        if (TextUtils.isEmpty(title)) return "";
        if (TextUtils.isEmpty(artist) || title.toLowerCase(java.util.Locale.ROOT)
                .contains(artist.toLowerCase(java.util.Locale.ROOT))) return title;
        return title + " " + artist;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RESTORE : intent.getAction();
        if (ACTION_AA_DISCONNECTED.equals(action)) {
            getSharedPreferences("carassistant_media_resume", MODE_PRIVATE).edit()
                    .putBoolean("aa_connected", false).apply();
            handler.removeCallbacks(restoreAttempt);
            handler.removeCallbacks(disconnectMonitor);
            handler.removeCallbacks(connectionStabilizer);
            HeadlessProjectionHost.releaseIfHeadless(this);
            MediaSessionBridge.notifyAndroidAutoDisconnected();
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        handler.removeCallbacks(restoreAttempt);
        handler.removeCallbacks(disconnectMonitor);
        handler.removeCallbacks(connectionStabilizer);
        completed = false;
        connectionStabilized = false;
        launchInFlight = false;
        launchAccepted = false;
        launchAttempts = 0;
        lastLaunchAt = 0L;
        verifyStartedAt = 0L;

        expectedPackage = MediaSessionBridge.getCheckpointPackage(this);
        if (TextUtils.isEmpty(expectedPackage)) {
            expectedPackage = MediaSessionBridge.getLastKnownPackage(this);
        }

        if (TextUtils.isEmpty(expectedPackage) || getPackageName().equals(expectedPackage)
                || !expectedPackage.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            Log.w(TAG, "No valid external media checkpoint package; phone recovery not started");
            // No restore work remains, so the bridge may start phone-side silent PCM.
            completed = true;
            MediaSessionBridge.notifyMediaRestoreFinished();
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                .putString("phase", "waiting_for_phone_app")
                .putBoolean("pending_projection_restore", false)
                .putBoolean("content_restore_requested", false)
                .remove("target_package")
                .remove("query")
                .remove("target_display_id")
                .remove("search_submitted_at")
                .remove("youtube_history_stage")
                .apply();

        updateForegroundStatus("Đang chờ Android Auto ổn định trước khi mở ứng dụng nguồn");
        handler.post(disconnectMonitor);
        handler.postDelayed(connectionStabilizer, INITIAL_SETTLE_MS);
        Log.i(TAG, "AA reconnect received; waiting 10 seconds of stable notification, then "
                + "launch source app on phone display 0 with Accessibility recovery: source="
                + expectedPackage);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(restoreAttempt);
        handler.removeCallbacks(disconnectMonitor);
        handler.removeCallbacks(connectionStabilizer);
        launchShell.destroy();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
