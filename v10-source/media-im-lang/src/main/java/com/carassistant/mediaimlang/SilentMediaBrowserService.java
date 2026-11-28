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
 * A persistent silent MediaSession backed by three 30-minute MP4 playlist items.
 * The AAC stream contains digital zeroes only and the MediaPlayer output is muted.
 */
public final class SilentMediaBrowserService extends MediaBrowserService {
    private static final String TAG = "MediaImLang";
    private static final String CHANNEL = "media_im_lang";
    private static final int NOTIFICATION_ID = 6103;
    private static final String ROOT_ID = "media_im_lang_root";
    private static final long TRACK_DURATION_MS = 30L * 60L * 1000L;
    private static final long SEEK_STEP_MS = 15000L;
    private static final String ACTION_NOTIFICATION_PLAY =
            "com.carassistant.mediaimlang.action.NOTIFICATION_PLAY";
    private static final String ACTION_NOTIFICATION_PAUSE =
            "com.carassistant.mediaimlang.action.NOTIFICATION_PAUSE";

    private static volatile SilentMediaBrowserService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSession session;
    private MediaPlayer player;
    private List<File> playlist = Collections.emptyList();
    private int currentTrackIndex;
    private boolean externalSessionPresent;
    private boolean windowRunning;
    private boolean playlistStartAttempted;
    private long playbackStartedAt;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        session = new MediaSession(this, "MediaImLangSession");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                handler.post(() -> resumeOrStart("Android Auto play command"));
            }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
                if (mediaId != null && mediaId.startsWith("silent_")) {
                    int index = parseTrackIndex(mediaId);
                    handler.post(() -> playTrack(index, 0L, "Android Auto selected playlist item"));
                }
            }
            @Override public void onPrepareFromMediaId(String mediaId, Bundle extras) {
                Log.i(TAG, "Android Auto prepared playlist item=" + mediaId);
            }
            @Override public void onPause() { handler.post(() -> pauseSilent("Android Auto pause command")); }
            @Override public void onStop() { handler.post(() -> pauseSilent("Android Auto stop command")); }
            @Override public void onSkipToNext() {
                handler.post(() -> playTrack((currentTrackIndex + 1) % playlistSize(),
                        0L, "MediaSession NEXT"));
            }
            @Override public void onSkipToPrevious() {
                handler.post(() -> playTrack((currentTrackIndex + playlistSize() - 1)
                        % playlistSize(), 0L, "MediaSession PREVIOUS"));
            }
            @Override public void onSeekTo(long position) {
                handler.post(() -> seekTo(position));
            }
            @Override public void onFastForward() {
                handler.post(() -> seekBy(SEEK_STEP_MS));
            }
            @Override public void onRewind() {
                handler.post(() -> seekBy(-SEEK_STEP_MS));
            }
        });
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setMetadata(new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Media Im Lặng")
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "CarAsistant")
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "Playlist media nền")
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, TRACK_DURATION_MS)
                .build());
        session.setActive(false);
        setSessionToken(session.getSessionToken());
        setPausedState();
        startForeground(NOTIFICATION_ID, buildNotification());
        Log.i(TAG, "Persistent playlist MediaSession created; 3 x 30-minute silent MP4 tracks");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_NOTIFICATION_PLAY.equals(action)) {
            handler.post(() -> resumeOrStart("phone media notification play button"));
            return Service.START_STICKY;
        }
        if (ACTION_NOTIFICATION_PAUSE.equals(action)) {
            handler.post(() -> pauseSilent("phone media notification pause button"));
            return Service.START_STICKY;
        }
        if (intent != null && "com.carassistant.v10.ACTION_AA_DISCONNECTED".equals(action)) {
            pauseSilent("Android Auto disconnected");
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }
        if (!externalSessionPresent && !playlistStartAttempted) {
            handler.postDelayed(() -> {
                if (!externalSessionPresent && !playlistStartAttempted) {
                    resumeOrStart("service started");
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
                .setMediaId("silent_01")
                .setTitle("Media Im Lặng · 01/03")
                .setSubtitle("30 phút · Không âm thanh")
                .setDescription("MP4 im lặng, không có tone hoặc tần số âm thanh")
                .build();
        result.sendResult(Collections.singletonList(
                new MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_PLAYABLE)));
    }

    static void onSessionAccessReady() {
        SilentMediaBrowserService service = instance;
        if (service != null) service.handler.post(() -> {
            if (!service.externalSessionPresent && !service.playlistStartAttempted) {
                service.resumeOrStart("media-session access ready");
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
            service.releasePlayer();
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
            releasePlayer();
            if (session != null) session.setActive(false);
            stopForeground(true);
            return;
        }

        playlistStartAttempted = false;
        showMediaNotification();
        handler.postDelayed(() -> {
            if (!externalSessionPresent && !windowRunning) {
                resumeOrStart("last external media session disappeared");
            }
        }, 700L);
    }

    private void resumeOrStart(String reason) {
        if (externalSessionPresent || windowRunning) return;
        if (!hasSessionAccess()) {
            updateNotification("Cần bật quyền Truy cập thông báo");
            Log.w(TAG, "Silent playlist withheld: Notification Access is not granted");
            return;
        }
        if (player != null) {
            try {
                player.start();
                windowRunning = true;
                playbackStartedAt = SystemClock.elapsedRealtime();
                publishPlaybackState(PlaybackState.STATE_PLAYING);
                showMediaNotification();
                Log.i(TAG, "Silent playlist resumed at track=" + (currentTrackIndex + 1)
                        + ", positionMs=" + safeCurrentPosition());
                return;
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to resume current silent track; preparing again", error);
                releasePlayer();
            }
        }
        playTrack(currentTrackIndex, 0L, reason);
    }

    private void playTrack(int index, long positionMs, String reason) {
        if (externalSessionPresent) return;
        if (!hasSessionAccess()) {
            updateNotification("Cần bật quyền Truy cập thông báo");
            return;
        }
        try {
            if (playlist.isEmpty()) playlist = SilentMp4Factory.getPlaylist(this);
            if (playlist.isEmpty()) throw new IllegalStateException("Silent playlist is empty");
            currentTrackIndex = Math.floorMod(index, playlist.size());
            File source = playlist.get(currentTrackIndex);
            releasePlayer();
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setVolume(0f, 0f);
            player.setDataSource(source.getAbsolutePath());
            player.setOnPreparedListener(mediaPlayer -> {
                if (externalSessionPresent) {
                    releasePlayer();
                    return;
                }
                long target = Math.max(0L, Math.min(positionMs, TRACK_DURATION_MS));
                if (target > 0L) mediaPlayer.seekTo((int) target);
                mediaPlayer.start();
                windowRunning = true;
                playlistStartAttempted = true;
                playbackStartedAt = SystemClock.elapsedRealtime();
                updateMetadata();
                session.setActive(true);
                publishPlaybackState(PlaybackState.STATE_PLAYING);
                showMediaNotification();
                Log.i(TAG, "Silent playlist track started index=" + (currentTrackIndex + 1)
                        + "/" + playlist.size() + ", durationMs=" + TRACK_DURATION_MS
                        + ", positionMs=" + target + ", reason=" + reason);
            });
            player.setOnCompletionListener(mediaPlayer -> {
                Log.i(TAG, "Silent playlist track completed; advancing automatically");
                playTrack((currentTrackIndex + 1) % playlistSize(), 0L, "automatic playlist advance");
            });
            player.setOnErrorListener((mediaPlayer, what, extra) -> {
                Log.e(TAG, "Silent playlist playback error what=" + what + " extra=" + extra);
                windowRunning = false;
                playlistStartAttempted = false;
                releasePlayer();
                setPausedState();
                return true;
            });
            player.prepareAsync();
        } catch (Exception error) {
            Log.e(TAG, "Unable to create or play silent playlist", error);
            playlistStartAttempted = false;
            updateNotification("Không tạo/phát được playlist MP4 im lặng");
            setPausedState();
        }
    }

    private void pauseSilent(String reason) {
        boolean wasRunning = windowRunning;
        if (player != null) {
            try { if (player.isPlaying()) player.pause(); } catch (RuntimeException ignored) { }
        }
        windowRunning = false;
        if (wasRunning) {
            setPausedState();
            Log.i(TAG, "Silent playlist paused: " + reason + ", positionMs=" + safeCurrentPosition());
        }
        if (session != null) session.setActive(!externalSessionPresent && hasSessionAccess());
        if (!externalSessionPresent) showMediaNotification();
    }

    private void seekBy(long offsetMs) {
        long target = Math.max(0L, Math.min(TRACK_DURATION_MS, safeCurrentPosition() + offsetMs));
        seekTo(target);
    }

    private void seekTo(long positionMs) {
        if (player == null) return;
        long target = Math.max(0L, Math.min(TRACK_DURATION_MS, positionMs));
        try {
            player.seekTo((int) target);
            publishPlaybackState(windowRunning
                    ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
            Log.i(TAG, "Silent playlist seek track=" + (currentTrackIndex + 1)
                    + ", positionMs=" + target);
        } catch (RuntimeException error) {
            Log.w(TAG, "Silent playlist seek rejected", error);
        }
    }

    private long safeCurrentPosition() {
        if (player == null) return 0L;
        try { return Math.max(0L, player.getCurrentPosition()); }
        catch (RuntimeException ignored) { return 0L; }
    }

    private int playlistSize() { return playlist == null || playlist.isEmpty() ? 3 : playlist.size(); }

    private int parseTrackIndex(String mediaId) {
        try { return Math.max(0, Integer.parseInt(mediaId.substring("silent_".length())) - 1); }
        catch (RuntimeException ignored) { return 0; }
    }

    private void updateMetadata() {
        if (session == null) return;
        String title = String.format(java.util.Locale.US, "Media Im Lặng · %02d/%02d",
                currentTrackIndex + 1, playlistSize());
        session.setMetadata(new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "CarAsistant")
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "Playlist media nền")
                .putString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID,
                        "silent_" + String.format(java.util.Locale.US, "%02d", currentTrackIndex + 1))
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, TRACK_DURATION_MS)
                .putLong(android.media.MediaMetadata.METADATA_KEY_TRACK_NUMBER, currentTrackIndex + 1)
                .putLong(android.media.MediaMetadata.METADATA_KEY_NUM_TRACKS, playlistSize())
                .build());
    }

    private void publishPlaybackState(int state) {
        if (session == null) return;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD
                | PlaybackState.ACTION_REWIND;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, safeCurrentPosition(), state == PlaybackState.STATE_PLAYING ? 1f : 0f)
                .setBufferedPosition(TRACK_DURATION_MS)
                .setActiveQueueItemId(currentTrackIndex)
                .build());
    }

    private void setPausedState() {
        if (session != null) {
            updateMetadata();
            publishPlaybackState(PlaybackState.STATE_PAUSED);
            if (!externalSessionPresent) showMediaNotification();
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.setOnPreparedListener(null); } catch (RuntimeException ignored) { }
            try { player.setOnCompletionListener(null); } catch (RuntimeException ignored) { }
            try { player.setOnErrorListener(null); } catch (RuntimeException ignored) { }
            try { player.stop(); } catch (RuntimeException ignored) { }
            try { player.release(); } catch (RuntimeException ignored) { }
            player = null;
        }
        windowRunning = false;
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
                .setContentText(playing
                        ? String.format(java.util.Locale.US, "Đang phát playlist · %02d/03",
                                currentTrackIndex + 1)
                        : "Đã tạm dừng · Playlist media nền")
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
