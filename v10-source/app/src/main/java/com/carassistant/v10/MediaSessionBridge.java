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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile Snapshot snapshot = Snapshot.empty();
    private static volatile MediaController activeController;
    private MediaSession mirrorSession;
    private MediaSessionManager manager;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private MediaController.Callback controllerCallback;
    private final Runnable resumePositionTicker = new Runnable() {
        @Override public void run() {
            MediaController controller = activeController;
            if (controller != null) persistResumeSnapshot(MediaSessionBridge.this, controller, false);
            if (manager != null) MAIN.postDelayed(this, 1000L);
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

    /** Current media position extrapolated from the last framework playback-state update. */
    public static long getCurrentPosition() {
        MediaController controller = activeController;
        if (controller == null) return Math.max(0L, snapshot.position);
        PlaybackState state = controller.getPlaybackState();
        if (state == null) return Math.max(0L, snapshot.position);
        long position = Math.max(0L, state.getPosition());
        if (state.getState() == PlaybackState.STATE_PLAYING
                && state.getLastPositionUpdateTime() > 0L) {
            long elapsed = Math.max(0L,
                    SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime());
            position += Math.round(elapsed * state.getPlaybackSpeed());
        }
        MediaMetadata metadata = controller.getMetadata();
        long duration = metadata == null ? 0L
                : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        return duration > 0L ? Math.min(position, duration) : position;
    }

    /** Mark whether the Android Auto CarActivity currently owns the projection session. */
    public static void setProjectionConnected(Context context, boolean connected) {
        if (context == null) return;
        context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("projection_connected", connected).commit();
    }

    /** Flush the last projected media state when the Android Auto activity stops. */
    public static void persistCurrentResumeSnapshot(Context context, boolean durable) {
        MediaController controller = activeController;
        if (context != null && controller != null) {
            persistResumeSnapshot(context.getApplicationContext(), controller, durable);
        }
    }

    private static void persistResumeSnapshot(
            Context context, MediaController controller, boolean durable) {
        if (context == null || controller == null) return;
        if (!context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
                .getBoolean("projection_connected", false)) return;
        ComponentName projected = V10SessionStore.getTarget(context);
        if (projected == null
                || !projected.getPackageName().equals(controller.getPackageName())) return;

        MediaMetadata metadata = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        String title = metadata == null ? "" :
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata == null ? "" :
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(artist) && metadata != null) {
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        }
        long duration = metadata == null ? 0L :
                Math.max(0L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        long position = getCurrentPosition();
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;

        android.content.SharedPreferences.Editor editor =
                context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE).edit()
                        .putString(LAST_PACKAGE, controller.getPackageName())
                        .putString("resume_projected_package", projected.getPackageName())
                        .putString("resume_projected_class", projected.getClassName())
                        .putString("resume_media_package", controller.getPackageName())
                        .putString("resume_title", title == null ? "" : title)
                        .putString("resume_artist", artist == null ? "" : artist)
                        .putLong("resume_duration", duration)
                        .putLong("resume_position", position)
                        .putBoolean("resume_was_playing", playing)
                        .putLong("resume_saved_at", System.currentTimeMillis())
                        .putBoolean("resume_valid", !TextUtils.isEmpty(title));
        if (durable) editor.commit();
        else editor.apply();
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
        }, MAIN);
    }

    private void updateMirrorSession(Snapshot state) {
        MediaSession session = mirrorSession;
        if (session == null) return;
        boolean hasPlayer = state != null && !TextUtils.isEmpty(state.packageName);
        if (!hasPlayer) {
            session.setMetadata(null);
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_NONE, 0L, 0f)
                    .build());
            session.setActive(false);
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

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND
                | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        Math.max(0L, state.position), state.playing ? 1f : 0f)
                .build());
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

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        if (V10SessionStore.getCurrentActivity() == null) {
            setProjectionConnected(this, false);
        }
        ensureMirrorSession();
        try {
            manager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return;
            sessionsListener = controllers -> refreshControllers(controllers);
            manager.addOnActiveSessionsChangedListener(
                    sessionsListener, new ComponentName(this, MediaSessionBridge.class));
            refreshControllers(manager.getActiveSessions(
                    new ComponentName(this, MediaSessionBridge.class)));
            MAIN.removeCallbacks(resumePositionTicker);
            MAIN.postDelayed(resumePositionTicker, 1000L);
            Log.i(TAG, "Notification access connected");
        } catch (SecurityException error) {
            Log.w(TAG, "Notification access is not granted", error);
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot read active media sessions", error);
        }
    }

    @Override
    public void onListenerDisconnected() {
        MAIN.removeCallbacks(resumePositionTicker);
        persistCurrentResumeSnapshot(this, true);
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
        MAIN.removeCallbacks(resumePositionTicker);
        persistCurrentResumeSnapshot(this, true);
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
        if (controllers != null) {
            for (MediaController candidate : controllers) {
                if (candidate == null || getPackageName().equals(candidate.getPackageName())) continue;
                PlaybackState state = candidate.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    chosen = candidate;
                    break;
                }
                if (chosen == null && candidate.getMetadata() != null) chosen = candidate;
            }
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
                persistResumeSnapshot(this, controller, false);
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
