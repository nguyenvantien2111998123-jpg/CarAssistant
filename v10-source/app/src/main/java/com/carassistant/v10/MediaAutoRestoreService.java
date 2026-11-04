package com.carassistant.v10;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
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
    private static volatile MediaAutoRestoreService ownerInstance;
    private MediaSession mirrorSession;
    private SteeringWheelMediaKeyHandler steeringKeyHandler;
    private MediaAudioRouteGate audioRouteGate;
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
                MediaSessionBridge.cancelCheckpointRestore(MediaAutoRestoreService.this);
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
                boolean contentAlreadyRequested = getSharedPreferences(
                        "media_autoplay", MODE_PRIVATE)
                        .getBoolean("content_restore_requested", false);
                if (contentAlreadyRequested) contentRestoreRequested = true;
                if (!exactItemReady && !contentRestoreRequested
                        && !contentAlreadyRequested && now - taskLaunchedAt >= 4000L) {
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
                // Do not issue an automatic Play while Android still exposes only
                // the phone's built-in outputs. AudioDeviceInfo reports connected
                // outputs (not the exact stream route), so this is a conservative
                // readiness gate and never attempts to force audio routing.
                boolean outputReady = !autoplay || (audioRouteGate != null
                        && audioRouteGate.isExternalOutputStable());
                if (!outputReady) {
                    Log.i(TAG, "Deferring checkpoint autoplay until an external output "
                            + "has remained available; phone speaker playback is not allowed");
                } else if (MediaSessionBridge.applyCheckpoint(
                        MediaAutoRestoreService.this, expectedPackage, autoplay)) {
                    checkpointApplied = true;
                    MediaSessionBridge.completeCheckpointRestore(MediaAutoRestoreService.this);
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
        ownerInstance = this;
        audioRouteGate = new MediaAudioRouteGate(this, handler, (stable, outputs) ->
                Log.i(TAG, "Audio output readiness=" + stable + "; outputs=" + outputs));
        audioRouteGate.start();
        createMirrorSession();
        updateMirrorSession(MediaSessionBridge.getSnapshot());
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
            MediaSessionBridge.cancelCheckpointRestore(this);
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
            // Keep the AA-facing controller available even when there is no saved source
            // item to restore. The disconnect monitor, not checkpoint availability, owns
            // this foreground service's lifetime.
            expectedPackage = null;
            expectedComponent = null;
            updateForegroundStatus("CarAsistant Media sẵn sàng điều khiển");
            Log.i(TAG, "No valid external media checkpoint; keeping controller session active");
            return START_STICKY;
        }

        MediaSessionBridge.beginCheckpointRestore(this, expectedPackage);
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
        releaseMirrorSession();
        if (audioRouteGate != null) {
            audioRouteGate.stop();
            audioRouteGate = null;
        }
        if (ownerInstance == this) ownerInstance = null;
        // If the service is stopped unexpectedly while the reader still owns the
        // Surface, release it. Once V10 takes over, releaseIfHeadless is a no-op.
        HeadlessProjectionHost.releaseIfHeadless(this);
        super.onDestroy();
    }

    /** Publish the latest phone-side source snapshot to the AA-facing controller session. */
    public static void updateMediaSession(Context context, MediaSessionBridge.Snapshot snapshot) {
        MediaAutoRestoreService owner = ownerInstance;
        if (owner == null) return;
        owner.handler.post(() -> owner.updateMirrorSession(snapshot));
    }

    private void createMirrorSession() {
        mirrorSession = new MediaSession(this, "CarAsistant Media Controller");
        mirrorSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        steeringKeyHandler = new SteeringWheelMediaKeyHandler(
                new SteeringWheelMediaKeyHandler.Actions() {
                    @Override public void onNext() { MediaSessionBridge.skipNext(); }
                    @Override public void onPrevious() { MediaSessionBridge.skipPrevious(); }
                    @Override public void onSeekForward() { MediaSessionBridge.seekBy(15000L); }
                    @Override public void onSeekBackward() { MediaSessionBridge.seekBy(-15000L); }
                    @Override public void onVoiceAssistant() {
                        Intent intent = new Intent("com.carassistant.v10.STEERING_VOICE")
                                .setPackage(getPackageName());
                        sendBroadcast(intent);
                    }
                });
        mirrorSession.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent intent) {
                if (steeringKeyHandler != null && steeringKeyHandler.handle(intent)) return true;
                return super.onMediaButtonEvent(intent);
            }
            @Override public void onPlay() { MediaSessionBridge.play(); }
            @Override public void onPause() { MediaSessionBridge.pause(); }
            @Override public void onSkipToNext() { MediaSessionBridge.skipNext(); }
            @Override public void onSkipToPrevious() { MediaSessionBridge.skipPrevious(); }
            @Override public void onFastForward() { MediaSessionBridge.seekBy(15000L); }
            @Override public void onRewind() { MediaSessionBridge.seekBy(-15000L); }
            @Override public void onSeekTo(long position) { MediaSessionBridge.seekTo(position); }
            @Override public void onStop() { MediaSessionBridge.pause(); }
            @Override public void onCustomAction(String action, Bundle extras) {
                if (MediaSessionBridge.ACTION_VOICE_ASSISTANT.equals(action)) {
                    Intent intent = new Intent("com.carassistant.v10.STEERING_VOICE")
                            .setPackage(getPackageName());
                    sendBroadcast(intent);
                }
            }
        }, handler);
        mirrorSession.setActive(true);
    }

    private void updateMirrorSession(MediaSessionBridge.Snapshot state) {
        MediaSession session = mirrorSession;
        if (session == null) return;
        boolean hasPlayer = state != null && !TextUtils.isEmpty(state.packageName);
        MediaMetadata.Builder metadata = new MediaMetadata.Builder();
        long position;
        long duration;
        boolean playing;
        if (hasPlayer) {
            metadata.putString(MediaMetadata.METADATA_KEY_TITLE,
                    TextUtils.isEmpty(state.title) ? "Đang phát media" : state.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST,
                            TextUtils.isEmpty(state.artist) ? state.packageName : state.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, state.packageName);
            position = Math.max(0L, state.position);
            duration = Math.max(0L, state.duration);
            playing = state.playing;
            Bitmap artwork = safeMirrorArtwork(state.artwork);
            if (artwork != null) metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
        } else {
            android.content.SharedPreferences prefs = getSharedPreferences(
                    "carassistant_media_resume", MODE_PRIVATE);
            String savedTitle = prefs.getString("checkpoint_title", "");
            String savedArtist = prefs.getString("checkpoint_artist", "");
            String savedPackage = prefs.getString("checkpoint_package", "");
            position = Math.max(0L, prefs.getLong("checkpoint_position", 0L));
            duration = Math.max(0L, prefs.getLong("checkpoint_duration", 0L));
            playing = false;
            metadata.putString(MediaMetadata.METADATA_KEY_TITLE,
                    TextUtils.isEmpty(savedTitle) ? "CarAsistant Media" : savedTitle)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST,
                            TextUtils.isEmpty(savedArtist)
                                    ? (TextUtils.isEmpty(savedPackage) ? "Trình điều khiển media" : savedPackage)
                                    : savedArtist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "CarAsistant");
        }
        if (duration > 0L) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
        session.setMetadata(metadata.build());
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
        PlaybackState playback = new PlaybackState.Builder().setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        duration > 0L ? Math.min(position, duration) : position,
                        playing ? 1f : 0f)
                .addCustomAction(new PlaybackState.CustomAction.Builder(
                        MediaSessionBridge.ACTION_VOICE_ASSISTANT,
                        "Trợ lý giọng nói CarAsistant",
                        android.R.drawable.ic_btn_speak_now).build())
                .build();
        session.setPlaybackState(playback);
        session.setActive(true);
    }

    private static Bitmap safeMirrorArtwork(Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        try {
            int width = source.getWidth();
            int height = source.getHeight();
            if (width <= 384 && height <= 384) return source;
            float scale = Math.min(384f / width, 384f / height);
            return Bitmap.createScaledBitmap(source,
                    Math.max(1, Math.round(width * scale)),
                    Math.max(1, Math.round(height * scale)), true);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not prepare mirrored media artwork", error);
            return null;
        }
    }

    private void releaseMirrorSession() {
        if (steeringKeyHandler != null) {
            steeringKeyHandler.dispose();
            steeringKeyHandler = null;
        }
        MediaSession session = mirrorSession;
        mirrorSession = null;
        if (session != null) {
            try {
                session.setActive(false);
                session.release();
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not release CarAsistant media session", error);
            }
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
