package com.carassistant.mediaimlang;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
            @Override public void onSkipToNext() { Log.i(TAG, "Next ignored: silent fallback has no playlist"); }
            @Override public void onSkipToPrevious() { Log.i(TAG, "Previous ignored: silent fallback has no playlist"); }
        });
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setMetadata(new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Media Im Lặng")
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "CarAsistant")
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "Media nền")
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, SILENT_WINDOW_MS)
                .build());
        session.setActive(true);
        setSessionToken(session.getSessionToken());
        setPausedState();
        startForeground(NOTIFICATION_ID, buildNotification("MediaSession đang hoạt động"));
        Log.i(TAG, "Persistent MediaSession created; silent bootstrap is one-shot");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
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
            return;
        }

        // A real app session disappeared. Allow exactly one new 3-second bootstrap.
        silentWindowAttempted = false;
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
                        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                        .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                        .build());
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
        // Do not rewrite the persistent notification on every PLAY/PAUSE transition.
    }

    private void setPausedState() {
        if (session != null) {
            session.setActive(true);
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                    .setState(PlaybackState.STATE_PAUSED, SILENT_WINDOW_MS, 0f)
                    .build());
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

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("Media Im Lặng")
                    .setContentText(text)
                    .setOngoing(true)
                    .build();
        }
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Media Im Lặng")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
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
