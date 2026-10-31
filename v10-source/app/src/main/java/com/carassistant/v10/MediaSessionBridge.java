package com.carassistant.v10;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reads the active phone media session so the CarAsistant dashboard can mirror
 * the currently playing app and send transport commands to that session.
 * The user must explicitly grant Notification Access in Android settings.
 */
public final class MediaSessionBridge extends NotificationListenerService {
    private static final String TAG = "CarAssistantMedia";
    private static final String RESUME_PREFS = "carassistant_media_resume";
    private static final String LAST_PACKAGE = "last_media_package";
    // A single rolling checkpoint: each minute replaces these keys; no history is retained.
    private static final long CHECKPOINT_INTERVAL_MS = 60_000L;
    private static final String CHECKPOINT_TITLE = "checkpoint_title";
    private static final String CHECKPOINT_ARTIST = "checkpoint_artist";
    private static final String CHECKPOINT_DURATION = "checkpoint_duration";
    private static final String CHECKPOINT_POSITION = "checkpoint_position";
    private static final String CHECKPOINT_PLAYING = "checkpoint_playing";
    private static final String CHECKPOINT_UPDATED_AT = "checkpoint_updated_at";
    public static final String ACTION_VOICE_ASSISTANT =
            "com.carassistant.v10.MEDIA_VOICE_ASSISTANT";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile Snapshot snapshot = Snapshot.empty();
    private static volatile MediaController activeController;
    private MediaSession mirrorSession;
    private MediaSessionManager manager;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private MediaController.Callback controllerCallback;
    private final Runnable checkpointTask = new Runnable() {
        @Override public void run() {
            saveCurrentCheckpoint();
            if (manager != null) MAIN.postDelayed(this, CHECKPOINT_INTERVAL_MS);
        }
    };

    public interface Listener {
        void onMediaChanged(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final String title;
        public final String artist;
        public final String packageName;
        public final Bitmap artwork;
        public final boolean playing;
        public final long duration;
        public final long position;

        private Snapshot(String title, String artist, String packageName, Bitmap artwork,
                         boolean playing, long duration, long position) {
            this.title = title;
            this.artist = artist;
            this.packageName = packageName;
            this.artwork = artwork;
            this.playing = playing;
            this.duration = duration;
            this.position = position;
        }

        static Snapshot empty() {
            return new Snapshot("", "", "", null, false, 0L, 0L);
        }
    }

    public static void addListener(Listener listener) {
        if (listener == null) return;
        LISTENERS.addIfAbsent(listener);
        MAIN.post(() -> listener.onMediaChanged(snapshot));
    }

    public static void removeListener(Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    public static Snapshot getSnapshot() {
        return snapshot;
    }

    /**
     * Persist one rolling media checkpoint every 60 seconds. The same preference keys
     * are overwritten on each run, so only the newest checkpoint is retained.
     */
    private void saveCurrentCheckpoint() {
        MediaController controller = activeController;
        if (controller == null) return; // Keep the last valid checkpoint if the player disappears.
        try {
            MediaMetadata metadata = controller.getMetadata();
            PlaybackState state = controller.getPlaybackState();
            String packageName = controller.getPackageName();
            String title = metadata == null ? "" :
                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata == null ? "" :
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (TextUtils.isEmpty(artist) && metadata != null) {
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            }
            long duration = metadata == null ? 0L :
                    Math.max(0L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            long position = state == null ? 0L : Math.max(0L, state.getPosition());
            boolean playing = state != null
                    && state.getState() == PlaybackState.STATE_PLAYING;
            // commit() makes this minute's checkpoint durable before the next interval.
            getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE).edit()
                    .putString(LAST_PACKAGE, packageName)
                    .putString(CHECKPOINT_TITLE, title == null ? "" : title)
                    .putString(CHECKPOINT_ARTIST, artist == null ? "" : artist)
                    .putLong(CHECKPOINT_DURATION, duration)
                    .putLong(CHECKPOINT_POSITION, position)
                    .putBoolean(CHECKPOINT_PLAYING, playing)
                    .putLong(CHECKPOINT_UPDATED_AT, System.currentTimeMillis())
                    .commit();
            Log.d(TAG, "Saved rolling checkpoint for " + packageName
                    + " at " + position + "ms; playing=" + playing);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not save media checkpoint", error);
        }
    }

    /** Package of the last media app with a published session, persisted across AA reconnects. */
    public static String getLastKnownPackage(Context context) {
        if (context == null) return null;
        String value = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
                .getString(LAST_PACKAGE, null);
        return TextUtils.isEmpty(value) ? null : value;
    }

    /**
     * Resume only the saved media app's session. Never send Play to an unrelated
     * player that happened to become active while Android Auto was reconnecting.
     */
    public static boolean resumePackage(String expectedPackage) {
        if (TextUtils.isEmpty(expectedPackage)) return false;
        MediaController controller = activeController;
        if (controller == null || !expectedPackage.equals(controller.getPackageName())) return false;
        PlaybackState state = controller.getPlaybackState();
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) return true;
        try {
            controller.getTransportControls().play();
            Log.i(TAG, "Requested resume for last media package " + expectedPackage);
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Last media session rejected Play", error);
            return false;
        }
    }

    public static boolean hasAccess(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(enabled)) return false;
        String expected = new ComponentName(context, MediaSessionBridge.class).flattenToString();
        for (String item : enabled.split(":")) {
            ComponentName parsed = ComponentName.unflattenFromString(item);
            if (parsed != null && expected.equals(parsed.flattenToString())) return true;
        }
        return false;
    }

    public static void requestAccess(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot open Notification Access settings", error);
        }
    }

    public static void play() {
        MediaController controller = activeController;
        if (controller == null) return;
        try {
            controller.getTransportControls().play();
        } catch (RuntimeException error) {
            Log.w(TAG, "Active media session rejected Play", error);
        }
    }

    public static void pause() {
        MediaController controller = activeController;
        if (controller == null) return;
        try {
            controller.getTransportControls().pause();
        } catch (RuntimeException error) {
            Log.w(TAG, "Active media session rejected Pause", error);
        }
    }

    public static void togglePlayPause() {
        MediaController controller = activeController;
        if (controller == null) return;
        PlaybackState state = controller.getPlaybackState();
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
            controller.getTransportControls().pause();
        } else {
            controller.getTransportControls().play();
        }
    }

    public static void skipNext() {
        MediaController controller = activeController;
        if (controller != null) controller.getTransportControls().skipToNext();
    }

    public static void skipPrevious() {
        MediaController controller = activeController;
        if (controller != null) controller.getTransportControls().skipToPrevious();
    }

    /** Seek the active media session to an absolute position in milliseconds. */
    public static void seekTo(long positionMs) {
        MediaController controller = activeController;
        if (controller == null) return;
        long target = Math.max(0L, positionMs);
        MediaMetadata metadata = controller.getMetadata();
        long duration = metadata == null ? 0L
                : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (duration > 0L) target = Math.min(target, duration);
        try {
            controller.getTransportControls().seekTo(target);
        } catch (RuntimeException error) {
            Log.w(TAG, "Active media session rejected seek request", error);
        }
    }

    /** Seek the active media session by a relative offset in milliseconds. */
    public static void seekBy(long offsetMs) {
        MediaController controller = activeController;
        if (controller == null || offsetMs == 0L) return;
        PlaybackState state = controller.getPlaybackState();
        if (state == null) return;
        seekTo(Math.max(0L, state.getPosition() + offsetMs));
    }

    private void ensureMirrorSession() {
        if (mirrorSession != null) return;
        mirrorSession = new MediaSession(this, "CarAsistant Media Controller");
        mirrorSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mirrorSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MediaSessionBridge.play(); }
            @Override public void onPause() { MediaSessionBridge.pause(); }
            @Override public void onSkipToNext() { MediaSessionBridge.skipNext(); }
            @Override public void onSkipToPrevious() { MediaSessionBridge.skipPrevious(); }
            @Override public void onFastForward() { MediaSessionBridge.seekBy(15000L); }
            @Override public void onRewind() { MediaSessionBridge.seekBy(-15000L); }
            @Override public void onSeekTo(long position) { MediaSessionBridge.seekTo(position); }
            @Override public void onStop() { MediaSessionBridge.pause(); }
            @Override public void onCustomAction(String action, Bundle extras) {
                if (ACTION_VOICE_ASSISTANT.equals(action)) {
                    Intent intent = new Intent("com.carassistant.v10.STEERING_VOICE")
                            .setPackage(getPackageName());
                    sendBroadcast(intent);
                }
            }
        }, MAIN);
    }

    private void updateMirrorSession(Snapshot state) {
        MediaSession session = mirrorSession;
        if (session == null) return;
        boolean hasPlayer = state != null && !TextUtils.isEmpty(state.packageName);
        if (!hasPlayer) {
            // Keep CarAsistant as the one stable media source on Android Auto Home/widget,
            // including when no phone player currently publishes a media session.
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "CarAsistant Media")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Trình điều khiển media")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "CarAsistant")
                    .build());
            session.setPlaybackState(buildMirrorPlaybackState(false, 0L, 0L));
            session.setActive(true);
            return;
        }

        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE,
                        TextUtils.isEmpty(state.title) ? "Đang phát media" : state.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST,
                        TextUtils.isEmpty(state.artist) ? state.packageName : state.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, state.packageName)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0L, state.duration));
        Bitmap artwork = safeMirrorArtwork(state.artwork);
        if (artwork != null) metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
        session.setMetadata(metadata.build());

        session.setPlaybackState(buildMirrorPlaybackState(
                state.playing, Math.max(0L, state.position), Math.max(0L, state.duration)));
        // Keep CarAsistant active while the source is paused or temporarily disappears.
        session.setActive(true);
    }

    private PlaybackState buildMirrorPlaybackState(boolean playing, long position, long duration) {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        return new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        duration > 0L ? Math.min(position, duration) : position,
                        playing ? 1f : 0f)
                .addCustomAction(new PlaybackState.CustomAction.Builder(
                        ACTION_VOICE_ASSISTANT, "Trợ lý giọng nói CarAsistant",
                        android.R.drawable.ic_btn_speak_now).build())
                .build();
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

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        ensureMirrorSession();
        try {
            manager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return;
            sessionsListener = controllers -> refreshControllers(controllers);
            manager.addOnActiveSessionsChangedListener(
                    sessionsListener, new ComponentName(this, MediaSessionBridge.class));
            refreshControllers(manager.getActiveSessions(
                    new ComponentName(this, MediaSessionBridge.class)));
            updateMirrorSession(snapshot);
            MAIN.removeCallbacks(checkpointTask);
            MAIN.postDelayed(checkpointTask, CHECKPOINT_INTERVAL_MS);
            Log.i(TAG, "Notification access connected; media checkpoint scheduled every 60 seconds");
        } catch (SecurityException error) {
            Log.w(TAG, "Notification access is not granted", error);
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot read active media sessions", error);
        }
    }

    @Override
    public void onListenerDisconnected() {
        MAIN.removeCallbacks(checkpointTask);
        if (manager != null && sessionsListener != null) {
            try { manager.removeOnActiveSessionsChangedListener(sessionsListener); }
            catch (RuntimeException ignored) { }
        }
        clearActiveController();
        releaseMirrorSession();
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        MAIN.removeCallbacks(checkpointTask);
        releaseMirrorSession();
        super.onDestroy();
    }

    private void releaseMirrorSession() {
        MediaSession session = mirrorSession;
        mirrorSession = null;
        if (session != null) {
            try {
                session.setActive(false);
                session.release();
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not release mirrored media session", error);
            }
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        refreshFromManager();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        refreshFromManager();
    }

    private void refreshFromManager() {
        if (manager == null) return;
        try {
            refreshControllers(manager.getActiveSessions(
                    new ComponentName(this, MediaSessionBridge.class)));
        } catch (RuntimeException error) {
            Log.d(TAG, "Active session refresh deferred", error);
        }
    }

    private void refreshControllers(List<MediaController> controllers) {
        MediaController chosen = null;
        MediaController savedCandidate = null;
        String lastPackage = getLastKnownPackage(this);
        if (controllers != null) {
            for (MediaController candidate : controllers) {
                if (candidate == null || getPackageName().equals(candidate.getPackageName())) continue;
                PlaybackState state = candidate.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    chosen = candidate;
                    break;
                }
                if (savedCandidate == null && lastPackage != null
                        && lastPackage.equals(candidate.getPackageName())) savedCandidate = candidate;
                if (chosen == null && candidate.getMetadata() != null) chosen = candidate;
            }
        }
        if (savedCandidate != null && (chosen == null
                || chosen.getPlaybackState() == null
                || chosen.getPlaybackState().getState() != PlaybackState.STATE_PLAYING)) {
            chosen = savedCandidate;
        }

        MediaController previous = activeController;
        if (previous != chosen) {
            if (previous != null && controllerCallback != null) {
                try { previous.unregisterCallback(controllerCallback); }
                catch (RuntimeException ignored) { }
            }
            activeController = chosen;
            controllerCallback = null;
            if (chosen != null) {
                final MediaController selectedController = chosen;
                controllerCallback = new MediaController.Callback() {
                    @Override public void onMetadataChanged(MediaMetadata metadata) {
                        publish(selectedController);
                    }
                    @Override public void onPlaybackStateChanged(PlaybackState state) {
                        publish(selectedController);
                    }
                    @Override public void onSessionDestroyed() {
                        refreshFromManager();
                    }
                };
                try { selectedController.registerCallback(controllerCallback, MAIN); }
                catch (RuntimeException error) { Log.w(TAG, "Cannot observe media controller", error); }
            }
        }
        publish(chosen);
    }

    private void publish(MediaController controller) {
        if (controller == null) {
            snapshot = Snapshot.empty();
        } else {
            try {
                getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE).edit()
                        .putString(LAST_PACKAGE, controller.getPackageName()).apply();
            } catch (RuntimeException error) {
                Log.d(TAG, "Could not persist last media package", error);
            }
            MediaMetadata metadata = controller.getMetadata();
            PlaybackState state = controller.getPlaybackState();
            String title = metadata == null ? "" : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata == null ? "" : metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (TextUtils.isEmpty(artist) && metadata != null) {
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            }
            Bitmap artwork = null;
            if (metadata != null) {
                artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (artwork == null) artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (artwork == null) artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
            long duration = metadata == null ? 0L
                    : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long position = state == null ? 0L : Math.max(0L, state.getPosition());
            boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
            snapshot = new Snapshot(
                    TextUtils.isEmpty(title) ? "Đang phát media" : title,
                    TextUtils.isEmpty(artist) ? controller.getPackageName() : artist,
                    controller.getPackageName(), artwork, playing, duration, position);
        }
        Snapshot latest = snapshot;
        MAIN.post(() -> {
            updateMirrorSession(latest);
            for (Listener listener : LISTENERS) {
                try { listener.onMediaChanged(latest); }
                catch (RuntimeException error) { Log.w(TAG, "Media UI callback failed", error); }
            }
        });
    }

    private void clearActiveController() {
        MediaController previous = activeController;
        if (previous != null && controllerCallback != null) {
            try { previous.unregisterCallback(controllerCallback); }
            catch (RuntimeException ignored) { }
        }
        activeController = null;
        controllerCallback = null;
        publish(null);
    }
}
