package com.carassistant.mediaimlang;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * A persistent Android Auto MediaSession with a one-shot 3-second silent bootstrap.
 * The MP4 is not a keep-alive loop: after it pauses, the MediaSession remains active.
 */
public final class SilentMediaBrowserService extends MediaBrowserService {
    private static final String TAG = "MediaImLang";
    private static final String CHANNEL = "media_im_lang";
    private static final int NOTIFICATION_ID = 6103;
    private static final String ROOT_ID = "media_im_lang_root";
    private static final long SILENT_WINDOW_MS = 3000L;
    private static final String ACTION_NOTIFICATION_PLAY = "com.carassistant.mediaimlang.action.NOTIFICATION_PLAY";
    private static final String ACTION_NOTIFICATION_PAUSE = "com.carassistant.mediaimlang.action.NOTIFICATION_PAUSE";

    private static volatile SilentMediaBrowserService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSession session;
    private MediaPlayer player;
    private boolean externalSessionPresent;
    private boolean windowRunning;
    private boolean silentWindowAttempted;
    private long playbackStartedAt;

    private final Runnable pauseAfterWindow = () -> pauseSilent("three-second window elapsed");

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        session = new MediaSession(this, "MediaImLangSession");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                // Android Auto may repeat PLAY while showing a paused card. Never
                // turn that into an endless sequence of 3-second playback windows.
                requestSilentWindow("Android Auto play command");
            }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
                if ("silent_3s".equals(mediaId)) requestSilentWindow("Android Auto selected silent item");
            }
            @Override public void onPrepareFromMediaId(String mediaId, Bundle extras) {
                Log.i(TAG, "Android Auto prepared media item=" + mediaId);
            }
            @Override public void onPause() { pauseSilent("Android Auto pause command"); }
            @Override public void onStop() { pauseSilent("Android Auto stop command"); }
            @Override public void onSkipToNext() {
                // Do not implement a second double-NEXT detector here. Hardware steering
                // keys are handled centrally by CarAsistant's existing MediaSession hook,
                // exactly as they are for YouTube/Zing. This fallback has no playlist.
                Log.d(TAG, "NEXT received by fallback session; steering gesture belongs to CarAsistant");
            }
            @Override public void onSkipToPrevious() {
                Log.d(TAG, "PREVIOUS ignored: silent fallback has no playlist");
            }
        });
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setMetadata(new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Media Im Lặng")
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "CarAsistant")
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "Media nền")
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, SILENT_WINDOW_MS)
                .build());
        // Keep the helper invisible to Android Auto until monitoring confirms that
        // no real phone media session is PLAYING or PAUSED.
        session.setActive(false);
        setSessionToken(session.getSessionToken());
        setPausedState();
        startForeground(NOTIFICATION_ID, buildNotification());
        Log.i(TAG, "Persistent MediaSession created; silent bootstrap is one-shot");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_NOTIFICATION_PLAY.equals(action)) {
            silentWindowAttempted = false;
            requestSilentWindow("phone media notification play button");
            return Service.START_STICKY;
        }
        if (ACTION_NOTIFICATION_PAUSE.equals(action)) {
            pauseSilent("phone media notification pause button");
            return Service.START_STICKY;
        }
        if (intent != null && "com.carassistant.v10.ACTION_AA_DISCONNECTED".equals(intent.getAction())) {
            pauseSilent("Android Auto disconnected");
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }
        if (!externalSessionPresent && !silentWindowAttempted) {
            // Give NotificationListenerService a moment to enumerate existing app sessions
            // after boot/AA reconnect, avoiding a race with YouTube or another real player.
            handler.postDelayed(() -> {
                if (!externalSessionPresent && !silentWindowAttempted) {
                    requestSilentWindow("service started");
                }
            }, 1800L);
        }
        return Service.START_STICKY;
    }

    @Override public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        if (!ROOT_ID.equals(parentId)) {
            result.sendResult(Collections.emptyList());
            return;
        }
        android.media.MediaDescription description = new android.media.MediaDescription.Builder()
                .setMediaId("silent_3s")
                .setTitle("Media Im Lặng")
                .setSubtitle("CarAsistant")
                .setDescription("Media nền không phát âm thanh")
                .build();
        result.sendResult(Collections.singletonList(
                new MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_PLAYABLE)));
    }

    static void onSessionAccessReady() {
        SilentMediaBrowserService service = instance;
        if (service != null) service.handler.post(() -> {
            if (!service.externalSessionPresent && !service.silentWindowAttempted) {
                service.requestSilentWindow("media-session access ready");
            }
        });
    }

    static void setExternalMediaSessionPresent(boolean present) {
        SilentMediaBrowserService service = instance;
        if (service != null) service.onExternalSessionChanged(present);
    }

    static void onSessionAccessLost() {
        SilentMediaBrowserService service = instance;
        if (service != null) service.handler.post(() -> {
            service.externalSessionPresent = false;
            service.pauseSilent("notification access disconnected");
            if (service.session != null) service.session.setActive(false);
            service.updateNotification("Cần bật quyền Truy cập thông báo");
            Log.w(TAG, "Media session monitoring unavailable; fallback session deactivated");
        });
    }

    private boolean hasSessionAccess() {
        if (Build.VERSION.SDK_INT < 27) return false;
        NotificationManager manager = getSystemService(NotificationManager.class);
        return manager != null && manager.isNotificationListenerAccessGranted(
                new ComponentName(this, MediaSessionWatcherService.class));
    }

    private void onExternalSessionChanged(boolean present) {
        if (externalSessionPresent == present) return;
        externalSessionPresent = present;
        Log.i(TAG, "External media session present=" + present);
        if (present) {
            pauseSilent("real media session has priority");
            // A paused helper session must not compete with a real app's paused
            // session in Android Auto's media source selection.
            if (session != null) session.setActive(false);
            stopForeground(true);
            return;
        }

        // A real app session disappeared. Allow exactly one new 3-second bootstrap.
        silentWindowAttempted = false;
        showMediaNotification();
        handler.postDelayed(() -> {
            if (!externalSessionPresent && !windowRunning) {
                requestSilentWindow("last external media session disappeared");
            }
        }, 700L);
    }

    private void requestSilentWindow(String reason) {
        if (externalSessionPresent || windowRunning || silentWindowAttempted) return;
        if (!hasSessionAccess()) {
            updateNotification("Cần bật quyền Truy cập thông báo");
            Log.w(TAG, "Silent bootstrap withheld: Notification Access is not granted");
            return;
        }

        // Latch before preparing to prevent duplicate PLAY/start requests racing.
        silentWindowAttempted = true;
        try {
            File mp4 = SilentMp4Factory.getOrCreate(this);
            releasePlayer();
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setVolume(0f, 0f);
            player.setDataSource(mp4.getAbsolutePath());
            player.setOnPreparedListener(mediaPlayer -> {
                if (externalSessionPresent) {
                    pauseSilent("external media session appeared before silent start");
                    return;
                }
                windowRunning = true;
                playbackStartedAt = SystemClock.elapsedRealtime();
                session.setActive(true);
                session.setPlaybackState(new PlaybackState.Builder()
                        // This helper has no playlist. Do not advertise fake skip actions;
                        // physical steering keys are intercepted centrally by CarAsistant.
                        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                        .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                        .build());
                showMediaNotification();
                mediaPlayer.start();
                handler.removeCallbacks(pauseAfterWindow);
                handler.postDelayed(pauseAfterWindow, SILENT_WINDOW_MS);
                Log.i(TAG, "Silent MP4 started once; volume=0; reason=" + reason);
            });
            player.setOnCompletionListener(mediaPlayer -> pauseSilent("silent MP4 completed"));
            player.setOnErrorListener((mediaPlayer, what, extra) -> {
                Log.e(TAG, "Silent MP4 playback error what=" + what + " extra=" + extra);
                pauseSilent("player error");
                return true;
            });
            player.prepareAsync();
        } catch (Exception error) {
            Log.e(TAG, "Unable to create or play silent MP4", error);
            updateNotification("Không tạo/phát được MP4 im lặng");
            setPausedState();
        }
    }

    private void pauseSilent(String reason) {
        handler.removeCallbacks(pauseAfterWindow);
        boolean wasRunning = windowRunning;
        if (player != null) {
            try { if (player.isPlaying()) player.pause(); } catch (RuntimeException ignored) { }
            releasePlayer();
        }
        windowRunning = false;
        if (wasRunning) {
            setPausedState();
            Log.i(TAG, "Silent MP4 paused: " + reason + ", elapsedMs="
                    + (playbackStartedAt == 0L ? 0L : SystemClock.elapsedRealtime() - playbackStartedAt));
        }
        if (session != null) {
            // Keep the fallback session active/PAUSED only while no real player exists.
            session.setActive(!externalSessionPresent && hasSessionAccess());
        }
        if (!externalSessionPresent) showMediaNotification();
    }

    private void setPausedState() {
        if (session != null) {
            session.setPlaybackState(new PlaybackState.Builder()
                     // No playlist or seekable content exists in this keepalive session.
                     // Steering events are routed by CarAsistant's system-level key hook.
                     .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                    .setState(PlaybackState.STATE_PAUSED, SILENT_WINDOW_MS, 0f)
                    .build());
            if (!externalSessionPresent) showMediaNotification();
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (RuntimeException ignored) { }
            player.release();
            player = null;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Media Im Lặng", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private PendingIntent notificationAction(String action, int requestCode) {
        Intent intent = new Intent(this, SilentMediaBrowserService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private Notification buildNotification() {
        boolean playing = windowRunning;
        Notification.Action primaryAction = new Notification.Action.Builder(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "Tạm dừng" : "Phát",
                notificationAction(playing ? ACTION_NOTIFICATION_PAUSE : ACTION_NOTIFICATION_PLAY,
                        playing ? 6105 : 6104))
                .build();

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Media Im Lặng")
                .setContentText(playing ? "Đang phát MP4 im lặng" : "Đã tạm dừng · Media nền")
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(primaryAction);

        if (session != null) {
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0));
        }
        return builder.build();
    }

    private void showMediaNotification() {
        if (externalSessionPresent) {
            stopForeground(true);
            return;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void updateNotification(String text) {
        if (externalSessionPresent) {
            stopForeground(true);
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        instance = null;
        super.onDestroy();
    }
}
