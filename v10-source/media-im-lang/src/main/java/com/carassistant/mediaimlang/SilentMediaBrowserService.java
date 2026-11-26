package com.carassistant.mediaimlang;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.browse.MediaBrowser;
import android.media.browse.MediaBrowserService;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.Collections;
import java.util.List;

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
    private boolean externalPlaying;
    private boolean windowRunning;
    private long playbackStartedAt;

    private final Runnable pauseAfterWindow = () -> pauseSilent("three-second window elapsed");

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        session = new MediaSession(this, "MediaImLangSession");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { startSilentWindow(); }
            @Override public void onPause() { pauseSilent("Android Auto pause command"); }
            @Override public void onStop() { pauseSilent("Android Auto stop command"); }
            @Override public void onSkipToNext() { startSilentWindow(); }
            @Override public void onSkipToPrevious() { startSilentWindow(); }
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
        startForeground(NOTIFICATION_ID, buildNotification("Sẵn sàng theo dõi MediaSession"));
        Log.i(TAG, "MediaBrowserService created; Android Auto media session is active");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "com.carassistant.v10.ACTION_AA_DISCONNECTED".equals(intent.getAction())) {
            pauseSilent("Android Auto disconnected");
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }
        if (!externalPlaying && !windowRunning) startSilentWindow();
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

    static void setExternalMediaPlaying(boolean playing) {
        SilentMediaBrowserService service = instance;
        if (service != null) service.onExternalPlaybackChanged(playing);
    }

    private void onExternalPlaybackChanged(boolean playing) {
        if (externalPlaying == playing) return;
        externalPlaying = playing;
        Log.i(TAG, "External media playing=" + playing);
        if (playing) {
            pauseSilent("real media session became active");
        } else {
            handler.postDelayed(() -> {
                if (!externalPlaying && !windowRunning) startSilentWindow();
            }, 500L);
        }
    }

    private void startSilentWindow() {
        if (externalPlaying || windowRunning) return;
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
                if (externalPlaying) {
                    pauseSilent("external media took priority before silent start");
                    return;
                }
                windowRunning = true;
                playbackStartedAt = SystemClock.elapsedRealtime();
                session.setPlaybackState(new PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                        .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                        .build());
                mediaPlayer.start();
                updateNotification("Đang chạy MP4 im lặng trong 3 giây");
                handler.removeCallbacks(pauseAfterWindow);
                handler.postDelayed(pauseAfterWindow, SILENT_WINDOW_MS);
                Log.i(TAG, "Silent MP4 started; volume=0, durationWindowMs=" + SILENT_WINDOW_MS);
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
        if (player != null) {
            try { if (player.isPlaying()) player.pause(); } catch (RuntimeException ignored) { }
        }
        windowRunning = false;
        setPausedState();
        updateNotification(externalPlaying ? "Đang nhường quyền cho media thật" : "Đã tạm dừng MP4 im lặng");
        Log.i(TAG, "Silent media paused: " + reason + ", elapsedMs="
                + (playbackStartedAt == 0L ? 0L : SystemClock.elapsedRealtime() - playbackStartedAt));
    }

    private void setPausedState() {
        if (session != null) {
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
