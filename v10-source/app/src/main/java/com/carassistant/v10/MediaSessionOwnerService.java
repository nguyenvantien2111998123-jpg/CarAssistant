package com.carassistant.v10;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

/**
 * Long-lived owner of the single CarAsistant MediaSession exposed to Android Auto.
 * It is deliberately separate from NotificationListenerService because Android may
 * disconnect/recreate notification listeners while the media surface must stay published.
 */
public final class MediaSessionOwnerService extends Service {
    private static final String TAG = "CarAssistantMediaOwner";
    private static final String CHANNEL_ID = "carassistant_media_owner";
    private static final int NOTIFICATION_ID = 7301;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile MediaSessionOwnerService instance;
    private static volatile MediaSessionBridge.Snapshot pendingSnapshot;
    private MediaSession session;

    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MediaSessionOwnerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** Called by MediaSessionBridge; never transfers ownership to the notification listener. */
    public static void publish(Context context, MediaSessionBridge.Snapshot snapshot) {
        pendingSnapshot = snapshot;
        MediaSessionOwnerService owner = instance;
        if (owner != null) {
            MAIN.post(() -> {
                MediaSessionOwnerService current = instance;
                if (current != null) current.applySnapshot(pendingSnapshot);
            });
        } else if (context != null) {
            Log.w(TAG, "Owner service is not running; retained latest media snapshot");
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        startAsForeground();
        ensureSession();
        applySnapshot(pendingSnapshot != null ? pendingSnapshot : MediaSessionBridge.getSnapshot());
        Log.i(TAG, "Independent CarAsistant MediaSession owner started");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        ensureSession();
        applySnapshot(pendingSnapshot != null ? pendingSnapshot : MediaSessionBridge.getSnapshot());
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "CarAsistant Media", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("CarAsistant Media")
                .setContentText("Đang duy trì bộ điều khiển media cho Android Auto")
                .setOngoing(true)
                .build();
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void ensureSession() {
        if (session != null) {
            try {
                if (!session.isActive()) session.setActive(true);
                return;
            } catch (RuntimeException stale) {
                Log.w(TAG, "Recreating stale MediaSession", stale);
                releaseSession();
            }
        }
        session = new MediaSession(this, "CarAsistant Media Controller");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent event) {
                if (SteeringWheelInputDispatcher.dispatch(MediaSessionOwnerService.this, event)) {
                    return true;
                }
                return super.onMediaButtonEvent(event);
            }
            @Override public void onPlay() { MediaSessionBridge.play(); }
            @Override public void onPause() { MediaSessionBridge.pause(); }
            @Override public void onSkipToNext() { MediaSessionBridge.skipNext(); }
            @Override public void onSkipToPrevious() { MediaSessionBridge.skipPrevious(); }
            @Override public void onFastForward() { MediaSessionBridge.seekBy(15000L); }
            @Override public void onRewind() { MediaSessionBridge.seekBy(-15000L); }
            @Override public void onSeekTo(long position) { MediaSessionBridge.seekTo(position); }
            @Override public void onStop() { MediaSessionBridge.pause(); }
            @Override public void onCustomAction(String action, android.os.Bundle extras) {
                if (MediaSessionBridge.ACTION_VOICE_ASSISTANT.equals(action)) {
                    sendBroadcast(new Intent("com.carassistant.v10.STEERING_VOICE")
                            .setPackage(getPackageName()));
                }
            }
        }, MAIN);
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "CarAsistant Media")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Trình điều khiển media")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "CarAsistant")
                .build());
        session.setPlaybackState(buildPlaybackState(false, 0L, 0L));
        session.setActive(true);
        Log.i(TAG, "Created and activated independent CarAsistant MediaSession");
    }

    private void applySnapshot(MediaSessionBridge.Snapshot state) {
        ensureSession();
        MediaSession target = session;
        if (target == null) return;
        try {
            boolean hasSource = state != null && !TextUtils.isEmpty(state.packageName);
            if (!hasSource) {
                String title = MediaSessionBridge.getCheckpointTitle(this);
                String artist = MediaSessionBridge.getCheckpointArtist(this);
                String source = MediaSessionBridge.getCheckpointPackage(this);
                android.content.SharedPreferences prefs = getSharedPreferences(
                        "carassistant_media_resume", Context.MODE_PRIVATE);
                long duration = Math.max(0L, prefs.getLong("checkpoint_duration", 0L));
                long position = MediaSessionBridge.getAdjustedCheckpointPosition(this);
                if (TextUtils.isEmpty(title)) {
                    title = "CarAsistant Media";
                    artist = "Trình điều khiển media";
                    source = "CarAsistant";
                }
                target.setMetadata(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST,
                                TextUtils.isEmpty(artist) ? source : artist)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM,
                                TextUtils.isEmpty(source) ? "CarAsistant" : source)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                        .build());
                target.setPlaybackState(buildPlaybackState(false, position, duration));
            } else {
                MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE,
                                TextUtils.isEmpty(state.title) ? "Đang phát media" : state.title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST,
                                TextUtils.isEmpty(state.artist) ? state.packageName : state.artist)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, state.packageName)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0L, state.duration));
                Bitmap art = safeArtwork(state.artwork);
                if (art != null) metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
                target.setMetadata(metadata.build());
                target.setPlaybackState(buildPlaybackState(
                        state.playing, Math.max(0L, state.position), Math.max(0L, state.duration)));
            }
            if (!target.isActive()) target.setActive(true);
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not update CarAsistant MediaSession", error);
            ensureSession();
        }
    }

    private PlaybackState buildPlaybackState(boolean playing, long position, long duration) {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
        return new PlaybackState.Builder().setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        duration > 0L ? Math.min(position, duration) : position, playing ? 1f : 0f)
                .addCustomAction(new PlaybackState.CustomAction.Builder(
                        MediaSessionBridge.ACTION_VOICE_ASSISTANT,
                        "Trợ lý giọng nói CarAsistant",
                        android.R.drawable.ic_btn_speak_now).build())
                .build();
    }

    private static Bitmap safeArtwork(Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        try {
            int w = source.getWidth(), h = source.getHeight();
            if (w <= 384 && h <= 384) return source;
            float scale = Math.min(384f / w, 384f / h);
            return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(w * scale)),
                    Math.max(1, Math.round(h * scale)), true);
        } catch (RuntimeException error) {
            Log.w(TAG, "Artwork conversion failed", error);
            return null;
        }
    }

    private void releaseSession() {
        MediaSession old = session;
        session = null;
        if (old != null) {
            try { old.setActive(false); old.release(); }
            catch (RuntimeException error) { Log.w(TAG, "Session release failed", error); }
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        releaseSession();
        super.onDestroy();
    }
}
