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
    private MediaSessionManager manager;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private MediaController.Callback controllerCallback;

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
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) pause();
        else play();
    }

    public static void skipNext() {
        MediaController controller = activeController;
        if (controller == null) return;
        try {
            controller.getTransportControls().skipToNext();
        } catch (RuntimeException error) {
            Log.w(TAG, "Active media session rejected Next", error);
        }
    }

    public static void skipPrevious() {
        MediaController controller = activeController;
        if (controller == null) return;
        try {
            controller.getTransportControls().skipToPrevious();
        } catch (RuntimeException error) {
            Log.w(TAG, "Active media session rejected Previous", error);
        }
    }

    /** Seek the active media session by a relative offset in milliseconds. */
    public static void seekBy(long offsetMs) {
        MediaController controller = activeController;
        if (controller == null || offsetMs == 0L) return;
        PlaybackState state = controller.getPlaybackState();
        if (state == null) return;
        long position = Math.max(0L, state.getPosition());
        seekTo(Math.max(0L, position + offsetMs));
    }

    /** Seek to an absolute position, clamped to the active item's duration when known. */
    public static void seekTo(long requestedPositionMs) {
        MediaController controller = activeController;
        if (controller == null) return;
        try {
            long target = Math.max(0L, requestedPositionMs);
            MediaMetadata metadata = controller.getMetadata();
            long duration = metadata == null ? 0L
                    : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (duration > 0L) target = Math.min(target, duration);
            controller.getTransportControls().seekTo(target);
        } catch (RuntimeException error) {
            Log.w(TAG, "Active media session rejected seek request", error);
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        try {
            manager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return;
            sessionsListener = controllers -> refreshControllers(controllers);
            manager.addOnActiveSessionsChangedListener(
                    sessionsListener, new ComponentName(this, MediaSessionBridge.class));
            refreshControllers(manager.getActiveSessions(
                    new ComponentName(this, MediaSessionBridge.class)));
            Log.i(TAG, "Notification access connected");
        } catch (SecurityException error) {
            Log.w(TAG, "Notification access is not granted", error);
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot read active media sessions", error);
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (manager != null && sessionsListener != null) {
            try { manager.removeOnActiveSessionsChangedListener(sessionsListener); }
            catch (RuntimeException ignored) { }
        }
        clearActiveController();
        super.onListenerDisconnected();
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
                if (candidate == null) continue;
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
