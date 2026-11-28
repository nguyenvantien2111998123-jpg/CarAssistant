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
    private static final long INITIAL_SETTLE_MS = 2500L;
    private static final long AA_CONNECTION_POLL_MS = 1000L;
    private static final long SESSION_RETRY_MS = 500L;
    private static final long VERIFY_WINDOW_MS = 15000L;
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
    private boolean mediaRecoveryFinished;
    private boolean homeWynRequested;

    private final Runnable disconnectMonitor = new Runnable() {
        @Override public void run() {
            boolean connected = getSharedPreferences("carassistant_media_resume", MODE_PRIVATE)
                    .getBoolean("aa_connected", false);
            if (!connected) {
                handler.removeCallbacks(restoreAttempt);
                handler.removeCallbacks(connectionStabilizer);
                V10PaneWarmupHost.releaseIfHeadless(MediaAutoRestoreService.this);
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
                stopForeground(true);
                stopSelf();
                return;
            }
            connectionStabilized = true;
            Log.i(TAG, "AA connection stable for " + INITIAL_SETTLE_MS
                    + " ms; phone-side media recovery may begin (no headless display)");
            if (TextUtils.isEmpty(expectedPackage)) finishMediaRecovery("no_real_media_checkpoint");
            else handler.post(restoreAttempt);
        }
    };

    private final Runnable restoreAttempt = new Runnable() {
        @Override public void run() {
            if (completed || !connectionStabilized) return;
            if (TextUtils.isEmpty(expectedPackage)) {
                finishMediaRecovery("no_real_media_checkpoint");
                return;
            }
            if (!getSharedPreferences("carassistant_media_resume", MODE_PRIVATE)
                    .getBoolean("aa_connected", false)) {
                Log.i(TAG, "AA is no longer connected; refusing media recovery");
                return;
            }

            if (!launchAccepted) {
                if (!launchInFlight) launchSourceAppOnPhone();
                return;
            }

            boolean playbackStateReached = false;
            if (MediaSessionBridge.isCheckpointMediaReady(
                    MediaAutoRestoreService.this, expectedPackage)) {
                boolean autoplay = getSharedPreferences("media_autoplay", MODE_PRIVATE)
                        .getBoolean("enabled", true);
                boolean positionRestoreComplete = MediaSessionBridge.applyCheckpoint(
                        MediaAutoRestoreService.this, expectedPackage, autoplay);
                playbackStateReached = MediaSessionBridge.isCheckpointPlaybackStateReached(
                        MediaAutoRestoreService.this, expectedPackage, autoplay);

                // Home is allowed only after BOTH requested PLAY/PAUSE state and
                // saved seek position are verified by applyCheckpoint().
                if (positionRestoreComplete) {
                    completed = true;
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

                    finishMediaRecovery("restore_success");
                    Log.i(TAG, "VERIFIED_PHONE_RESTORE source=" + expectedPackage
                            + ", requestedState=" + (autoplay ? "PLAY" : "PAUSE")
                            + ", mediaRecoveryFinished=" + mediaRecoveryFinished);
                    handler.removeCallbacks(restoreAttempt);
                    return;
                }
            }

            if (playbackStateReached) {
                // Match the previously validated sequence: once the exact saved item
                // reports the requested PLAY/PAUSE state, return the phone to Home and
                // start WYN after one second. Seek-position verification continues in
                // the background and does not hold the phone on the media app.
                if (requestHomeThenWyn("playback_state_reached")) {
                    Log.i(TAG, "MEDIA_PLAYBACK_STATE_READY source=" + expectedPackage
                            + "; Home requested immediately; seek verification continues");
                }
            }
            if (verifyStartedAt == 0L) verifyStartedAt = SystemClock.elapsedRealtime();
            if (SystemClock.elapsedRealtime() - verifyStartedAt < VERIFY_WINDOW_MS) {
                handler.postDelayed(this, SESSION_RETRY_MS);
            } else {
                Log.w(TAG, "Phone-side restore verification expired; checkpoint retained; "
                        + "continue to Home then WYN");
                finishMediaRecovery("restore_verification_timeout");
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

    /**
     * Finish only the media-recovery workflow. Persist the app task for immediate
     * projection when V10 opens, then publish a completion event. A separate
     * receiver backgrounds the phone task and starts the independent WYN flow.
     */
    private void finishMediaRecovery(String reason) {
        if (mediaRecoveryFinished) return;
        mediaRecoveryFinished = true;
        completed = true;
        handler.removeCallbacks(restoreAttempt);

        android.content.SharedPreferences.Editor projection =
                getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                        .putBoolean("media_projection_ready", !TextUtils.isEmpty(expectedPackage))
                        .putString("media_projection_result", reason)
                        .putLong("media_projection_ready_at", System.currentTimeMillis())
                        .putBoolean("pending_projection_restore", false)
                        .putBoolean("content_restore_requested", false);
        if (!TextUtils.isEmpty(expectedPackage)) {
            ComponentName component = expectedComponent;
            if (component == null) {
                component = MediaSessionBridge.getCheckpointTaskComponent(this);
            }
            if (component == null) {
                Intent launch = getPackageManager().getLaunchIntentForPackage(expectedPackage);
                component = launch == null ? null : launch.getComponent();
            }
            projection.putString("media_projection_package", expectedPackage);
            if (component != null && expectedPackage.equals(component.getPackageName())) {
                projection.putString("media_projection_component", component.flattenToString());
            } else {
                projection.remove("media_projection_component");
            }
        } else {
            projection.remove("media_projection_package")
                    .remove("media_projection_component");
        }
        projection.commit();

        updateForegroundStatus("Đã kết thúc khôi phục media");
        V10CarActivity.notifyMediaProjectionReady();
        requestHomeThenWyn(reason);
        Log.i(TAG, "MEDIA_RECOVERY_FINISHED reason=" + reason
                + "; projectionReady=" + !TextUtils.isEmpty(expectedPackage)
                + "; homeWynRequested=" + homeWynRequested);
    }

    /** One-shot handoff: Home now, then WYN exactly one second after Home command. */
    private boolean requestHomeThenWyn(String reason) {
        if (homeWynRequested) return false;
        homeWynRequested = true;
        try {
            Intent finished = new Intent(MediaRestoreCompleteReceiver.ACTION)
                    .setComponent(new ComponentName(this, MediaRestoreCompleteReceiver.class))
                    .putExtra("restore_result", reason)
                    .putExtra("source_package", expectedPackage == null ? "" : expectedPackage);
            sendBroadcast(finished);
            Log.i(TAG, "MEDIA_HOME_WYN_HANDOFF reason=" + reason);
            return true;
        } catch (RuntimeException error) {
            homeWynRequested = false;
            Log.e(TAG, "Could not publish Home/WYN handoff: " + reason, error);
            return false;
        }
    }

    private void launchSourceAppOnPhone() {
        if (launchAttempts >= MAX_LAUNCH_ATTEMPTS) {
            Log.e(TAG, "Phone source launch exhausted retries for " + expectedPackage);
            finishMediaRecovery("source_launch_retries_exhausted");
            return;
        }

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(expectedPackage);
        expectedComponent = launchIntent == null ? null : launchIntent.getComponent();
        if (expectedComponent == null) {
            expectedComponent = MediaSessionBridge.getCheckpointTaskComponent(this);
        }
        if (expectedComponent == null
                || !expectedPackage.equals(expectedComponent.getPackageName())) {
            Log.e(TAG, "No launchable source Activity for " + expectedPackage);
            finishMediaRecovery("source_activity_unavailable");
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
                    Log.e(TAG, "Source app launch failed after all retries; checkpoint retained");
                    finishMediaRecovery("source_launch_failed");
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
            V10PaneWarmupHost.releaseIfHeadless(this);
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        handler.removeCallbacks(restoreAttempt);
        handler.removeCallbacks(disconnectMonitor);
        handler.removeCallbacks(connectionStabilizer);
        completed = false;
        connectionStabilized = false;
        mediaRecoveryFinished = false;
        homeWynRequested = false;
        boolean paneWarm = V10PaneWarmupHost.start(this);
        Log.i(TAG, "Visible media pane prewarm at AA connect=" + paneWarm);
        getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                .putBoolean("media_projection_ready", false)
                .remove("media_projection_package")
                .remove("media_projection_component")
                .apply();
        launchInFlight = false;
        launchAccepted = false;
        launchAttempts = 0;
        lastLaunchAt = 0L;
        verifyStartedAt = 0L;

        expectedPackage = MediaSessionBridge.getCheckpointPackage(this);
        // The standalone silent session exists only to keep Android Auto's media
        // surface discoverable. It is never a user content source to restore.
        if ("com.carassistant.mediaimlang".equals(expectedPackage)) {
            Log.i(TAG, "Ignoring non-restorable Silent Media checkpoint; preserving last real player");
            expectedPackage = null;
        }
        if (TextUtils.isEmpty(expectedPackage)) {
            expectedPackage = MediaSessionBridge.getLastKnownPackage(this);
        }
        if ("com.carassistant.mediaimlang".equals(expectedPackage)) {
            Log.i(TAG, "Ignoring Silent Media last-known package; no real source to relaunch");
            expectedPackage = null;
        }

        if (TextUtils.isEmpty(expectedPackage) || getPackageName().equals(expectedPackage)
                || !expectedPackage.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            // No real checkpoint: do not synthesize playback. End this workflow
            // cleanly; the independent completion receiver handles WYN afterward.
            expectedPackage = null;
            Log.i(TAG, "No real media checkpoint; media recovery has nothing to restore");
            updateForegroundStatus("Không có media cần khôi phục");
            handler.post(disconnectMonitor);
            handler.postDelayed(connectionStabilizer, INITIAL_SETTLE_MS);
            return START_STICKY;
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
        Log.i(TAG, "AA reconnect received; waiting 2.5 seconds of stable notification, then "
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
