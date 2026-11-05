package com.carassistant.v10;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;

/**
 * Background-only media recovery.
 *
 * This service never launches a source Activity, creates a virtual display, moves a task,
 * or invokes Accessibility UI automation. It waits for the source app to publish a real
 * MediaSession, then asks MediaSessionBridge to restore and verify the saved checkpoint.
 */
public final class MediaAutoRestoreService extends Service {
    public static final String ACTION_RESTORE =
            "com.carassistant.v10.action.RESTORE_MEDIA_ON_AA_CONNECT";
    public static final String ACTION_AA_DISCONNECTED =
            "com.carassistant.v10.action.AA_DISCONNECTED";

    private static final String TAG = "CarAssistantMediaResume";
    private static final String RESUME_PREFS = "carassistant_media_resume";
    private static final String AUTOPLAY_PREFS = "media_autoplay";
    private static final String CHANNEL = "carassistant_media_restore";
    private static final int NOTIFICATION_ID = 4038;
    private static final long INITIAL_SETTLE_MS = 4000L;
    private static final long SESSION_RETRY_MS = 1500L;
    private static final long DISCONNECT_CHECK_MS = 5000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessionManager;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private String expectedPackage = "";
    private long restoreNotBefore;
    private boolean hasExpectedSession;
    private String lastStatus = "";

    private final Runnable disconnectMonitor = new Runnable() {
        @Override public void run() {
            if (!isAndroidAutoConnected()) {
                stopRecovery("Android Auto disconnected; background recovery stopped");
                return;
            }
            handler.postDelayed(this, DISCONNECT_CHECK_MS);
        }
    };

    private final Runnable retryCurrentSession = new Runnable() {
        @Override public void run() {
            if (!isAndroidAutoConnected()) {
                stopRecovery("Android Auto disconnected while waiting for media session");
                return;
            }
            inspectCurrentSessions();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID,
                buildNotification("Đang chờ phiên media hợp lệ từ ứng dụng nguồn"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RESTORE : intent.getAction();
        if (ACTION_AA_DISCONNECTED.equals(action)) {
            stopRecovery("Android Auto disconnected");
            return START_NOT_STICKY;
        }

        if (!isAndroidAutoConnected()) {
            stopRecovery("Không còn tín hiệu Android Auto Connected");
            return START_NOT_STICKY;
        }

        handler.removeCallbacks(disconnectMonitor);
        handler.removeCallbacks(retryCurrentSession);
        expectedPackage = MediaSessionBridge.getCheckpointPackage(this);
        if (TextUtils.isEmpty(expectedPackage)) {
            expectedPackage = MediaSessionBridge.getLastKnownPackage(this);
        }
        if (TextUtils.isEmpty(expectedPackage)
                || getPackageName().equals(expectedPackage)
                || !expectedPackage.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            Log.w(TAG, "No valid external source package in the durable media checkpoint");
            stopRecovery("Không có checkpoint media hợp lệ");
            return START_NOT_STICKY;
        }

        restoreNotBefore = SystemClock.elapsedRealtime() + INITIAL_SETTLE_MS;
        hasExpectedSession = false;
        getSharedPreferences(AUTOPLAY_PREFS, MODE_PRIVATE).edit()
                .putString("phase", "background_waiting_for_session")
                .putString("target_package", expectedPackage)
                .putLong("background_wait_started_at", System.currentTimeMillis())
                .apply();

        handler.postDelayed(disconnectMonitor, DISCONNECT_CHECK_MS);
        registerSessionObserver();
        updateForegroundStatus("Đang chờ MediaSession của " + expectedPackage);
        Log.i(TAG, "Background-only recovery started; waiting for source MediaSession: "
                + expectedPackage);
        handler.postDelayed(retryCurrentSession, INITIAL_SETTLE_MS);
        return START_STICKY;
    }

    private boolean isAndroidAutoConnected() {
        return getSharedPreferences(RESUME_PREFS, MODE_PRIVATE)
                .getBoolean("aa_connected", false);
    }

    private void registerSessionObserver() {
        try {
            sessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            if (sessionManager == null) {
                Log.e(TAG, "MediaSessionManager unavailable; cannot observe source sessions");
                updateForegroundStatus("Không thể theo dõi MediaSession");
                return;
            }
            ComponentName notificationListener =
                    new ComponentName(this, MediaSessionBridge.class);
            sessionsListener = controllers -> {
                if (!isAndroidAutoConnected()) {
                    stopRecovery("Android Auto disconnected");
                    return;
                }
                handler.removeCallbacks(retryCurrentSession);
                handler.postDelayed(retryCurrentSession, 250L);
            };
            sessionManager.addOnActiveSessionsChangedListener(
                    sessionsListener, notificationListener, handler);
            inspectCurrentSessions();
        } catch (SecurityException error) {
            Log.e(TAG, "Notification access is required to observe active media sessions", error);
            updateForegroundStatus("Cần bật quyền Notification Access");
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not register active-session observer", error);
            updateForegroundStatus("Đang chờ kết nối MediaSession");
        }
    }

    private void inspectCurrentSessions() {
        if (TextUtils.isEmpty(expectedPackage) || !isAndroidAutoConnected()
                || sessionManager == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now < restoreNotBefore) {
            handler.postDelayed(retryCurrentSession, restoreNotBefore - now);
            return;
        }

        final List<MediaController> controllers;
        try {
            controllers = sessionManager.getActiveSessions(
                    new ComponentName(this, MediaSessionBridge.class));
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not read active media sessions; will wait for the next change", error);
            updateForegroundStatus("Đang chờ quyền truy cập phiên media");
            return;
        }

        boolean packageSessionFound = false;
        if (controllers != null) {
            for (MediaController controller : controllers) {
                if (controller != null
                        && expectedPackage.equals(controller.getPackageName())) {
                    packageSessionFound = true;
                    break;
                }
            }
        }

        if (!packageSessionFound) {
            hasExpectedSession = false;
            setWaitingState("background_waiting_for_session",
                    "Đang chờ ứng dụng tạo MediaSession: " + expectedPackage);
            // No polling while the source has no session. The active-session listener
            // wakes this service when Android registers a new source session.
            return;
        }

        hasExpectedSession = true;
        if (!MediaSessionBridge.isCheckpointMediaReady(this, expectedPackage)) {
            setWaitingState("background_waiting_for_exact_media",
                    "Đã phát hiện session; đang chờ đúng nội dung checkpoint");
            // Poll only while the expected package has a live session, because metadata
            // can arrive after session registration without another session-list event.
            handler.removeCallbacks(retryCurrentSession);
            handler.postDelayed(retryCurrentSession, SESSION_RETRY_MS);
            return;
        }

        boolean autoplay = getSharedPreferences(AUTOPLAY_PREFS, MODE_PRIVATE)
                .getBoolean("enabled", true);
        if (MediaSessionBridge.applyCheckpoint(this, expectedPackage, autoplay)) {
            getSharedPreferences(AUTOPLAY_PREFS, MODE_PRIVATE).edit()
                    .putString("phase", "background_exact_restored")
                    .putLong("background_restored_at", System.currentTimeMillis())
                    .apply();
            updateForegroundStatus(autoplay
                    ? "Đã xác minh khôi phục đúng media và trạng thái PLAY"
                    : "Đã xác minh khôi phục đúng media và trạng thái PAUSE");
            Log.i(TAG, "EXACT_RESTORED source=" + expectedPackage
                    + ", autoplay=" + autoplay);
            stopRecovery("Exact media checkpoint restored and verified");
            return;
        }

        setWaitingState("background_verifying_exact_media",
                "Đang xác minh vị trí và trạng thái phát thực tế");
        handler.removeCallbacks(retryCurrentSession);
        handler.postDelayed(retryCurrentSession, SESSION_RETRY_MS);
    }

    private void setWaitingState(String phase, String message) {
        getSharedPreferences(AUTOPLAY_PREFS, MODE_PRIVATE).edit()
                .putString("phase", phase)
                .putString("target_package", expectedPackage)
                .apply();
        updateForegroundStatus(message);
    }

    private void updateForegroundStatus(String message) {
        if (TextUtils.equals(lastStatus, message)) return;
        lastStatus = message;
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(message));
        Log.i(TAG, message);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "CarAsistant media recovery", NotificationManager.IMPORTANCE_LOW));
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

    private void stopRecovery(String reason) {
        Log.i(TAG, reason);
        handler.removeCallbacks(disconnectMonitor);
        handler.removeCallbacks(retryCurrentSession);
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(disconnectMonitor);
        handler.removeCallbacks(retryCurrentSession);
        if (sessionManager != null && sessionsListener != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not unregister active-session observer", error);
            }
        }
        sessionsListener = null;
        sessionManager = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
