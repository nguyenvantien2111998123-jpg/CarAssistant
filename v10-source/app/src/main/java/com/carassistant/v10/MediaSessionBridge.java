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
    // A single rolling checkpoint: frequent durable writes keep abrupt-power-loss recovery close to real time.
    private static final long CHECKPOINT_INTERVAL_MS = 5_000L;
    private static final String CHECKPOINT_PACKAGE = "checkpoint_package";
    private static final String CHECKPOINT_TASK_COMPONENT = "checkpoint_task_component";
    private static final String CHECKPOINT_TITLE = "checkpoint_title";
    private static final String CHECKPOINT_ARTIST = "checkpoint_artist";
    private static final String CHECKPOINT_MEDIA_ID = "checkpoint_media_id";
    private static final String CHECKPOINT_MEDIA_URI = "checkpoint_media_uri";
    private static final String CHECKPOINT_DURATION = "checkpoint_duration";
    private static final String CHECKPOINT_POSITION = "checkpoint_position";
    private static final String CHECKPOINT_PLAYING = "checkpoint_playing";
    private static final String CHECKPOINT_UPDATED_AT = "checkpoint_updated_at";
    private static final String CHECKPOINT_SPEED = "checkpoint_speed";
    private static final String CHECKPOINT_ELAPSED_REALTIME = "checkpoint_elapsed_realtime";
    private static final String GEARHEAD_PACKAGE = "com.google.android.projection.gearhead";
    private static final String AA_TRIGGER_PREF = "last_aa_notification_restore";
    private static final String AA_TRIGGER_ELAPSED_PREF = "last_aa_notification_restore_elapsed";
    private static final String AA_CONNECTED_PREF = "aa_connected";
    private static final String AA_NOTIFICATION_ID_PREF = "aa_notification_id";
    private static final String AA_NOTIFICATION_TAG_PREF = "aa_notification_tag";
    private static final long AA_TRIGGER_COOLDOWN_MS = 90000L;
    public static final String ACTION_VOICE_ASSISTANT =
            "com.carassistant.v10.MEDIA_VOICE_ASSISTANT";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile Snapshot snapshot = Snapshot.empty();
    private static volatile MediaController activeController;
    private static volatile MediaController checkpointController;
    // Package-matched session candidate used only for non-playing prepareFromMediaId.
    private static volatile MediaController checkpointPackageController;
    private static volatile long lastCheckpointPosition;
    private static volatile long lastCheckpointDuration;
    private static volatile MediaSessionBridge listenerInstance;
    private static volatile RestoreAttempt restoreAttempt;
    private static final long RESTORE_POSITION_TOLERANCE_MS = 3000L;
    private static final long RESTORE_COMMAND_TIMEOUT_MS = 6000L;
    private static final int RESTORE_MAX_SEEK_ATTEMPTS = 3;

    private static final class RestoreAttempt {
        final MediaController controller;
        final long checkpointUpdatedAt;
        final long targetPosition;
        final boolean autoplay;
        int seekAttempts;
        boolean seekSent;
        boolean seekConfirmed;
        boolean transportSent;
        long seekSentAt;
        long transportSentAt;
        RestoreAttempt(MediaController controller, long checkpointUpdatedAt,
                       long targetPosition, boolean autoplay) {
            this.controller = controller;
            this.checkpointUpdatedAt = checkpointUpdatedAt;
            this.targetPosition = targetPosition;
            this.autoplay = autoplay;
        }
    }
    private MediaSessionManager manager;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private MediaController.Callback controllerCallback;
    private final Runnable checkpointTask = new Runnable() {
        @Override public void run() {
            saveCurrentCheckpoint();
            if (manager != null) MAIN.postDelayed(this, CHECKPOINT_INTERVAL_MS);
        }
    };
    // Retry only while no source controller is available. A valid controller is
    // observed through MediaController.Callback and does not need periodic polling.
    private final Runnable controllerRefreshTask = new Runnable() {
        @Override public void run() {
            if (manager == null) return;
            String pendingPackage = getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
                    .getString("checkpoint_restore_package", "");
            if (activeController != null
                    && (TextUtils.isEmpty(pendingPackage) || checkpointController != null)) return;
            refreshFromManager();
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
     * Persist one rolling media checkpoint every five seconds. The same preference keys
     * are overwritten on each run, so only the newest checkpoint is retained.
     */
    private void saveCurrentCheckpoint() {
        MediaController controller = activeController;
        if (controller == null) return;
        try {
            MediaMetadata metadata = controller.getMetadata();
            PlaybackState state = controller.getPlaybackState();
            String packageName = controller.getPackageName();
            if (TextUtils.isEmpty(packageName) || metadata == null) return;

            MediaCheckpointStore.Checkpoint previous = MediaCheckpointStore.load(this);
            boolean sameItem = previous != null && packageName.equals(previous.packageName)
                    && matchesSavedMediaIdentity(previous, metadata);
            android.content.SharedPreferences prefs =
                    getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE);
            boolean restorePending = prefs.getBoolean("checkpoint_restore_pending", false);
            String restorePackage = prefs.getString("checkpoint_restore_package", "");
            if (restorePending) {
                // Never let a startup placeholder, a different app, or a different
                // track overwrite the checkpoint while the old session is being restored.
                if (!packageName.equals(restorePackage) || !sameItem) return;
            }

            String title = safeMetadataString(metadata, MediaMetadata.METADATA_KEY_TITLE);
            String artist = safeMetadataString(metadata, MediaMetadata.METADATA_KEY_ARTIST);
            if (TextUtils.isEmpty(artist)) {
                artist = safeMetadataString(metadata, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            }
            String mediaId = safeMetadataString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID);
            String mediaUri = safeMetadataString(metadata, MediaMetadata.METADATA_KEY_MEDIA_URI);
            if (TextUtils.isEmpty(title) && TextUtils.isEmpty(mediaId)
                    && TextUtils.isEmpty(mediaUri)) return;

            long duration = Math.max(0L,
                    metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            if ((state == null || state.getPosition() < 0L) && !sameItem) {
                // Do not replace a good checkpoint with a session that has not
                // published a usable playback position yet.
                return;
            }
            long position = state == null || state.getPosition() < 0L
                    ? previous.position : state.getPosition();
            if (state != null && state.getPosition() >= 0L
                    && state.getState() == PlaybackState.STATE_PLAYING
                    && state.getPlaybackSpeed() > 0f && state.getLastPositionUpdateTime() > 0L) {
                long stateAge = Math.max(0L, android.os.SystemClock.elapsedRealtime()
                        - state.getLastPositionUpdateTime());
                position += (long) (stateAge * state.getPlaybackSpeed());
            }
            if (duration > 0L) position = Math.min(position, duration);

            ComponentName projectedTask = V10SessionStore.getTarget(this);
            String taskComponent = projectedTask != null
                    && packageName.equals(projectedTask.getPackageName())
                    ? projectedTask.flattenToString() : "";
            if (TextUtils.isEmpty(taskComponent)) {
                try {
                    Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent != null && launchIntent.getComponent() != null) {
                        taskComponent = launchIntent.getComponent().flattenToString();
                    }
                } catch (RuntimeException ignored) { }
            }

            boolean playing = state == null ? previous.playing
                    : state.getState() == PlaybackState.STATE_PLAYING;
            float speed = playing && state != null ? state.getPlaybackSpeed()
                    : (state == null ? previous.speed : 1f);
            long elapsedRealtime = android.os.SystemClock.elapsedRealtime();
            long updatedAt = System.currentTimeMillis();
            MediaCheckpointStore.Checkpoint checkpoint = new MediaCheckpointStore.Checkpoint(
                    packageName, taskComponent, title, artist, mediaId, mediaUri,
                    duration, position, playing, speed, elapsedRealtime, updatedAt);
            MediaCheckpointStore.save(this, checkpoint);
            lastCheckpointPosition = position;
            lastCheckpointDuration = duration;
            Log.d(TAG, "Queued AtomicFile checkpoint for " + packageName
                    + " at " + position + "ms; playing=" + playing);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not capture media checkpoint", error);
        }
    }

    /** Mark the durable checkpoint as protected while the saved task/session is reconstructed. */
    public static void beginCheckpointRestore(Context context, String expectedPackage) {
        if (context == null || TextUtils.isEmpty(expectedPackage)) return;
        context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("checkpoint_restore_pending", true)
                .putString("checkpoint_restore_package", expectedPackage)
                .commit();
        synchronized (MediaSessionBridge.class) { restoreAttempt = null; }
        MediaSessionBridge listener = listenerInstance;
        if (listener != null) MAIN.post(listener::refreshFromManager);
    }

    /** Called only after seek position and the requested Play/Pause state are confirmed. */
    public static void completeCheckpointRestore(Context context) {
        if (context == null) return;
        context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE).edit()
                .remove("checkpoint_restore_pending")
                .remove("checkpoint_restore_package")
                .commit();
        synchronized (MediaSessionBridge.class) { restoreAttempt = null; }
        MediaSessionBridge listener = listenerInstance;
        if (listener != null) MAIN.post(() -> {
            listener.saveCurrentCheckpoint();
            listener.refreshFromManager();
        });
    }

    /** Clear the restore guard after an explicit Android Auto disconnect. */
    public static void cancelCheckpointRestore(Context context) {
        if (context == null) return;
        context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE).edit()
                .remove("checkpoint_restore_pending")
                .remove("checkpoint_restore_package")
                .commit();
        synchronized (MediaSessionBridge.class) { restoreAttempt = null; }
    }

        /** Package of the last media app with a published session, persisted across AA reconnects. */
    public static String getLastKnownPackage(Context context) {
        if (context == null) return null;
        String value = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
                .getString(LAST_PACKAGE, null);
        return TextUtils.isEmpty(value) ? null : value;
    }

    /** Exact app Activity previously projected by V10, when it belongs to the saved media package. */
    public static ComponentName getCheckpointTaskComponent(Context context) {
        MediaCheckpointStore.Checkpoint checkpoint = MediaCheckpointStore.load(context);
        if (checkpoint == null || TextUtils.isEmpty(checkpoint.taskComponent)) return null;
        return ComponentName.unflattenFromString(checkpoint.taskComponent);
    }

    /** Stable content identifier supplied by the source MediaSession, when available. */
    public static String getCheckpointMediaId(Context context) {
        MediaCheckpointStore.Checkpoint checkpoint = MediaCheckpointStore.load(context);
        return checkpoint == null || TextUtils.isEmpty(checkpoint.mediaId)
                ? null : checkpoint.mediaId;
    }

    /** Deep link supplied by the source MediaSession, when available. */
    public static String getCheckpointMediaUri(Context context) {
        MediaCheckpointStore.Checkpoint checkpoint = MediaCheckpointStore.load(context);
        return checkpoint == null || TextUtils.isEmpty(checkpoint.mediaUri)
                ? null : checkpoint.mediaUri;
    }

    public static String getCheckpointTitle(Context context) {
        MediaCheckpointStore.Checkpoint checkpoint = MediaCheckpointStore.load(context);
        return checkpoint == null || TextUtils.isEmpty(checkpoint.title)
                ? null : checkpoint.title;
    }

    public static String getCheckpointArtist(Context context) {
        MediaCheckpointStore.Checkpoint checkpoint = MediaCheckpointStore.load(context);
        return checkpoint == null || TextUtils.isEmpty(checkpoint.artist)
                ? null : checkpoint.artist;
    }

    private static String safeMetadataString(MediaMetadata metadata, String key) {
        try {
            String value = metadata.getString(key);
            return value == null ? "" : value;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /** Package associated with the newest complete checkpoint. */
    public static String getCheckpointPackage(Context context) {
        MediaCheckpointStore.Checkpoint checkpoint = MediaCheckpointStore.load(context);
        return checkpoint == null || TextUtils.isEmpty(checkpoint.packageName)
                ? null : checkpoint.packageName;
    }

    /**
     * Reconstruct the checkpoint position after abrupt power loss. Since the player
     * stops when the phone loses power, extrapolation is capped to one checkpoint
     * interval; time spent with the phone powered off must never advance the track.
     */
    private static long getAdjustedCheckpointPosition(Context context) {
        MediaCheckpointStore.Checkpoint checkpoint = MediaCheckpointStore.load(context);
        if (checkpoint == null) return 0L;
        long position = checkpoint.position;
        if (checkpoint.playing && checkpoint.elapsedRealtime > 0L
                && android.os.SystemClock.elapsedRealtime() >= checkpoint.elapsedRealtime
                && checkpoint.speed > 0f) {
            long elapsed = Math.min(
                    android.os.SystemClock.elapsedRealtime() - checkpoint.elapsedRealtime,
                    CHECKPOINT_INTERVAL_MS);
            position += (long) (elapsed * checkpoint.speed);
        }
        return checkpoint.duration > 0L
                ? Math.min(position, checkpoint.duration) : position;
    }

    /**
     * A package match alone is insufficient: many players publish a session before
     * restoring the selected track. Wait until the saved item identity is visible so
     * the old seek position cannot be applied to a different song/video.
     */
    private static boolean matchesSavedMediaIdentity(
            MediaCheckpointStore.Checkpoint expected, MediaMetadata current) {
        if (expected == null || current == null) return false;
        String actualId = safeMetadataString(current, MediaMetadata.METADATA_KEY_MEDIA_ID);
        String actualUri = safeMetadataString(current, MediaMetadata.METADATA_KEY_MEDIA_URI);
        String actualTitle = safeMetadataString(current, MediaMetadata.METADATA_KEY_TITLE);
        String actualArtist = safeMetadataString(current, MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(actualArtist)) {
            actualArtist = safeMetadataString(current, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        }
        if (!TextUtils.isEmpty(expected.mediaId)) {
            return expected.mediaId.equals(actualId);
        }
        if (!TextUtils.isEmpty(expected.mediaUri)) {
            return expected.mediaUri.equals(actualUri);
        }
        if (TextUtils.isEmpty(expected.title) || TextUtils.isEmpty(actualTitle)
                || !expected.title.trim().equalsIgnoreCase(actualTitle.trim())) return false;
        return TextUtils.isEmpty(expected.artist)
                || (!TextUtils.isEmpty(actualArtist)
                && expected.artist.trim().equalsIgnoreCase(actualArtist.trim()));
    }

    /** True only when the saved source package is publishing the exact saved media item. */
    public static boolean isCheckpointMediaReady(Context context, String expectedPackage) {
        if (context == null || TextUtils.isEmpty(expectedPackage)) return false;
        MediaController controller = checkpointController;
        if (controller == null || !expectedPackage.equals(controller.getPackageName())) {
            controller = activeController;
        }
        if (controller == null || !expectedPackage.equals(controller.getPackageName())) return false;
        MediaCheckpointStore.Checkpoint checkpoint = MediaCheckpointStore.load(context);
        try {
            return matchesSavedMediaIdentity(checkpoint, controller.getMetadata());
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not verify checkpoint media identity", error);
            return false;
        }
    }

    /**
     * Ask the checkpoint package's session to prepare the saved item by stable media ID.
     * This uses PREPARE (never PLAY) so content can load without emitting phone-speaker audio.
     */
    public static boolean prepareCheckpointMediaId(Context context, String expectedPackage) {
        if (context == null || TextUtils.isEmpty(expectedPackage)) return false;
        MediaCheckpointStore.Checkpoint checkpoint = MediaCheckpointStore.load(context);
        if (checkpoint == null || TextUtils.isEmpty(checkpoint.mediaId)
                || !expectedPackage.equals(checkpoint.packageName)) return false;
        MediaController controller = checkpointPackageController;
        if (controller == null || !expectedPackage.equals(controller.getPackageName())) {
            controller = activeController;
        }
        if (controller == null || !expectedPackage.equals(controller.getPackageName())) return false;
        try {
            PlaybackState state = controller.getPlaybackState();
            if (state == null || (state.getActions()
                    & PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID) == 0L) return false;
            controller.getTransportControls().prepareFromMediaId(checkpoint.mediaId, null);
            Log.i(TAG, "Requested non-playing mediaId preparation for " + expectedPackage);
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Source session rejected prepareFromMediaId", error);
            return false;
        }
    }

    /** Apply the saved position and explicit autoplay preference to the matching player only. */
    public static boolean applyCheckpoint(Context context, String expectedPackage, boolean autoplay) {
        if (context == null || TextUtils.isEmpty(expectedPackage)) return false;
        MediaController controller = checkpointController;
        if (controller == null || !expectedPackage.equals(controller.getPackageName())) {
            controller = activeController;
        }
        if (controller == null || !expectedPackage.equals(controller.getPackageName())) return false;

        MediaCheckpointStore.Checkpoint checkpoint = MediaCheckpointStore.load(context);
        if (checkpoint == null || !expectedPackage.equals(checkpoint.packageName)) return false;
        try {
            if (!matchesSavedMediaIdentity(checkpoint, controller.getMetadata())) {
                synchronized (MediaSessionBridge.class) { restoreAttempt = null; }
                Log.i(TAG, "Waiting for exact saved media identity before issuing transport commands");
                return false;
            }

            long target = getAdjustedCheckpointPosition(context);
            if (checkpoint.duration > 0L) target = Math.min(target, checkpoint.duration);
            long now = android.os.SystemClock.elapsedRealtime();
            RestoreAttempt attempt;
            synchronized (MediaSessionBridge.class) {
                attempt = restoreAttempt;
                if (attempt == null || attempt.controller != controller
                        || attempt.checkpointUpdatedAt != checkpoint.updatedAt
                        || attempt.targetPosition != target || attempt.autoplay != autoplay) {
                    attempt = new RestoreAttempt(controller, checkpoint.updatedAt, target, autoplay);
                    restoreAttempt = attempt;
                }
            }

            PlaybackState state = controller.getPlaybackState();
            long actualPosition = state == null ? -1L : state.getPosition();
            if (!attempt.seekSent) {
                controller.getTransportControls().seekTo(target);
                attempt.seekSent = true;
                attempt.seekSentAt = now;
                attempt.seekAttempts++;
                Log.i(TAG, "Checkpoint seek requested at " + target + "ms; attempt "
                        + attempt.seekAttempts);
                return false;
            }

            if (!attempt.seekConfirmed) {
                if (actualPosition >= 0L
                        && Math.abs(actualPosition - target) <= RESTORE_POSITION_TOLERANCE_MS) {
                    attempt.seekConfirmed = true;
                    Log.i(TAG, "Checkpoint seek confirmed at " + actualPosition + "ms");
                } else if (now - attempt.seekSentAt >= RESTORE_COMMAND_TIMEOUT_MS) {
                    if (attempt.seekAttempts >= RESTORE_MAX_SEEK_ATTEMPTS) {
                        synchronized (MediaSessionBridge.class) {
                            if (restoreAttempt == attempt) restoreAttempt = null;
                        }
                        Log.w(TAG, "Source player did not confirm seek; restore remains pending");
                        return false;
                    }
                    controller.getTransportControls().seekTo(target);
                    attempt.seekSentAt = now;
                    attempt.seekAttempts++;
                    Log.w(TAG, "Seek acknowledgement timed out; retry "
                            + attempt.seekAttempts + "/" + RESTORE_MAX_SEEK_ATTEMPTS);
                    return false;
                } else {
                    return false;
                }
            }

            if (!attempt.transportSent) {
                if (autoplay) controller.getTransportControls().play();
                else controller.getTransportControls().pause();
                attempt.transportSent = true;
                attempt.transportSentAt = now;
                Log.i(TAG, "Checkpoint transport requested; autoplay=" + autoplay);
                return false;
            }

            boolean stateConfirmed = state != null && (autoplay
                    ? state.getState() == PlaybackState.STATE_PLAYING
                    : state.getState() == PlaybackState.STATE_PAUSED);
            long expectedPosition = target;
            if (autoplay && now >= attempt.transportSentAt) {
                expectedPosition += (long) ((now - attempt.transportSentAt)
                        * Math.max(0f, checkpoint.speed));
                if (checkpoint.duration > 0L) {
                    expectedPosition = Math.min(expectedPosition, checkpoint.duration);
                }
            }
            boolean positionConfirmed = actualPosition >= 0L
                    && Math.abs(actualPosition - expectedPosition) <= 5000L;
            if (stateConfirmed && positionConfirmed) {
                synchronized (MediaSessionBridge.class) {
                    if (restoreAttempt == attempt) restoreAttempt = null;
                }
                Log.i(TAG, "Checkpoint restore confirmed: package=" + expectedPackage
                        + ", position=" + actualPosition + ", playing=" + autoplay);
                return true;
            }

            if (now - attempt.transportSentAt >= RESTORE_COMMAND_TIMEOUT_MS) {
                if (attempt.seekAttempts >= RESTORE_MAX_SEEK_ATTEMPTS) {
                    synchronized (MediaSessionBridge.class) {
                        if (restoreAttempt == attempt) restoreAttempt = null;
                    }
                } else {
                    attempt.seekSent = false;
                    attempt.seekConfirmed = false;
                    attempt.transportSent = false;
                    Log.w(TAG, "Playback state did not confirm; retrying seek/transport sequence");
                }
            }
            return false;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not apply/verify media checkpoint", error);
            return false;
        }
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
        logSteeringTarget("NEXT", controller);
        if (controller != null) {
            try {
                controller.getTransportControls().skipToNext();
            } catch (RuntimeException error) {
                Log.w(TAG, "Steering NEXT rejected by " + controller.getPackageName(), error);
            }
        }
    }

    public static void skipPrevious() {
        MediaController controller = activeController;
        logSteeringTarget("PREVIOUS", controller);
        if (controller != null) {
            try {
                controller.getTransportControls().skipToPrevious();
            } catch (RuntimeException error) {
                Log.w(TAG, "Steering PREVIOUS rejected by " + controller.getPackageName(), error);
            }
        }
    }

    private static void logSteeringTarget(String action, MediaController controller) {
        if (controller == null) {
            Log.w(TAG, "STEERING " + action + " received by CarAsistant Media but no source session is active");
            return;
        }
        try {
            MediaMetadata metadata = controller.getMetadata();
            PlaybackState state = controller.getPlaybackState();
            String title = metadata == null ? "" :
                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            Log.i(TAG, "STEERING " + action + " routed by CarAsistant Media"
                    + " source=" + controller.getPackageName()
                    + " title=" + (title == null ? "" : title)
                    + " playbackState=" + (state == null ? "null" : state.getState()));
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not inspect steering target for " + action, error);
        }
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

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        listenerInstance = this;
        try {
            manager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return;
            sessionsListener = controllers -> refreshControllers(controllers);
            manager.addOnActiveSessionsChangedListener(
                    sessionsListener, new ComponentName(this, MediaSessionBridge.class));
            refreshControllers(manager.getActiveSessions(
                    new ComponentName(this, MediaSessionBridge.class)));
            MediaAutoRestoreService.updateMediaSession(this, snapshot);
            // NotificationListenerService is rebound by Android after reboot.
            // Inspect current notifications so a reconnect that happened during boot is not missed.
            try {
                StatusBarNotification[] active = getActiveNotifications();
                // Do not trust a connection bit persisted by the previous boot.
                // Rebuild it only from a currently present, explicitly connected notification.
                getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(AA_CONNECTED_PREF, false).apply();
                if (active != null) {
                    for (StatusBarNotification notification : active) {
                        handleAndroidAutoConnectionNotification(notification);
                    }
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not inspect active Android Auto notifications", error);
            }
            MAIN.removeCallbacks(checkpointTask);
            MAIN.removeCallbacks(controllerRefreshTask);
            saveCurrentCheckpoint();
            MAIN.postDelayed(checkpointTask, CHECKPOINT_INTERVAL_MS);
            Log.i(TAG, "Notification access connected; AtomicFile checkpoint scheduled every 5 seconds");
        } catch (SecurityException error) {
            Log.w(TAG, "Notification access is not granted", error);
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot read active media sessions", error);
        }
    }

    @Override
    public void onListenerDisconnected() {
        MAIN.removeCallbacks(checkpointTask);
        MAIN.removeCallbacks(controllerRefreshTask);
        if (manager != null && sessionsListener != null) {
            try { manager.removeOnActiveSessionsChangedListener(sessionsListener); }
            catch (RuntimeException ignored) { }
        }
        manager = null;
        sessionsListener = null;
        if (listenerInstance == this) listenerInstance = null;
        // Keep the last complete AtomicFile checkpoint if NotificationListener is rebound.
        saveCurrentCheckpoint();
        clearActiveController();
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        MAIN.removeCallbacks(checkpointTask);
        MAIN.removeCallbacks(controllerRefreshTask);
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Gearhead's connection notification is the only notification signal used
        // for reconnect orchestration. MediaSession callbacks observe source playback.
        if (sbn == null || !GEARHEAD_PACKAGE.equals(sbn.getPackageName())) return;
        handleAndroidAutoConnectionNotification(sbn);
    }

    /**
     * Android Auto's phone-side notification is the single reconnect signal.
     * USB power/data state and Gearhead Activity lifecycle are deliberately not used.
     */
    private void handleAndroidAutoConnectionNotification(StatusBarNotification sbn) {
        if (sbn == null || !GEARHEAD_PACKAGE.equals(sbn.getPackageName())) return;
        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) return;

        Bundle extras = notification.extras;
        StringBuilder content = new StringBuilder();
        appendNotificationText(content, extras.getCharSequence(Notification.EXTRA_TITLE));
        appendNotificationText(content, extras.getCharSequence(Notification.EXTRA_TEXT));
        appendNotificationText(content, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        appendNotificationText(content, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        appendNotificationText(content, extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        String message = content.toString().toLowerCase(java.util.Locale.ROOT);

        android.content.SharedPreferences prefs = getSharedPreferences(
                RESUME_PREFS, Context.MODE_PRIVATE);
        String notificationTag = sbn.getTag() == null ? "" : sbn.getTag();
        boolean sameTrackedNotification = sbn.getId() == prefs.getInt(AA_NOTIFICATION_ID_PREF, -1)
                && notificationTag.equals(prefs.getString(AA_NOTIFICATION_TAG_PREF, ""));
        boolean disconnected = message.contains("disconnected")
                || message.contains("not connected")
                || message.contains("đã ngắt kết nối")
                || message.contains("mất kết nối");
        boolean connected = !disconnected && (message.contains("connected")
                || message.contains("đã kết nối")
                || message.contains("connecté")
                || message.contains("verbunden"));

        if (disconnected) {
            if (sameTrackedNotification) {
                prefs.edit().putBoolean(AA_CONNECTED_PREF, false)
                        .remove(AA_TRIGGER_PREF).remove(AA_TRIGGER_ELAPSED_PREF)
                        .remove(AA_NOTIFICATION_ID_PREF).remove(AA_NOTIFICATION_TAG_PREF)
                        .commit();
                cancelCheckpointRestore(this);
                stopService(new Intent(this, MediaAutoRestoreService.class));
                Log.i(TAG, "Tracked Android Auto notification changed to disconnected");
            }
            return;
        }
        if (!connected) return;

        prefs.edit().putBoolean(AA_CONNECTED_PREF, true)
                .putInt(AA_NOTIFICATION_ID_PREF, sbn.getId())
                .putString(AA_NOTIFICATION_TAG_PREF, notificationTag)
                .apply();
        long now = System.currentTimeMillis();
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        long previous = prefs.getLong(AA_TRIGGER_PREF, 0L);
        long previousElapsed = prefs.getLong(AA_TRIGGER_ELAPSED_PREF, 0L);
        boolean sameBoot = previousElapsed > 0L && nowElapsed >= previousElapsed;
        if (sameBoot && previous > 0L && now - previous < AA_TRIGGER_COOLDOWN_MS) return;

        Intent restore = new Intent(this, MediaAutoRestoreService.class)
                .setAction(MediaAutoRestoreService.ACTION_RESTORE);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(restore);
            else startService(restore);
            prefs.edit().putLong(AA_TRIGGER_PREF, now)
                    .putLong(AA_TRIGGER_ELAPSED_PREF, nowElapsed).apply();
            Log.i(TAG, "Android Auto connected notification detected; media restore requested");
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not start media restore after Android Auto notification", error);
        }
    }

    private static void appendNotificationText(StringBuilder target, CharSequence value) {
        if (value == null || value.length() == 0) return;
        if (target.length() > 0) target.append(' ');
        target.append(value);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !GEARHEAD_PACKAGE.equals(sbn.getPackageName())) return;
        android.content.SharedPreferences prefs =
                getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE);
        int expectedId = prefs.getInt(AA_NOTIFICATION_ID_PREF, -1);
        String expectedTag = prefs.getString(AA_NOTIFICATION_TAG_PREF, "");
        String removedTag = sbn.getTag() == null ? "" : sbn.getTag();
        if (sbn.getId() == expectedId && removedTag.equals(expectedTag)) {
            prefs.edit().putBoolean(AA_CONNECTED_PREF, false)
                    .remove(AA_TRIGGER_PREF)
                    .remove(AA_TRIGGER_ELAPSED_PREF)
                    .remove(AA_NOTIFICATION_ID_PREF)
                    .remove(AA_NOTIFICATION_TAG_PREF)
                    .apply();
            cancelCheckpointRestore(this);
            stopService(new Intent(this, MediaAutoRestoreService.class));
            Log.i(TAG, "Android Auto connection notification removed; restore service stopped");
        }
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
        MediaController checkpointCandidate = null;
        MediaController playingCandidate = null;
        MediaController metadataCandidate = null;
        MediaController savedCandidate = null;
        MediaCheckpointStore.Checkpoint checkpoint = MediaCheckpointStore.load(this);
        String checkpointPackage = checkpoint == null ? getLastKnownPackage(this)
                : checkpoint.packageName;
        String lastPackage = getLastKnownPackage(this);
        android.content.SharedPreferences prefs =
                getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE);
        boolean restorePending = prefs.getBoolean("checkpoint_restore_pending", false);
        boolean previousBootCheckpoint = checkpoint != null
                && checkpoint.elapsedRealtime > android.os.SystemClock.elapsedRealtime();

        if (controllers != null) {
            for (MediaController candidate : controllers) {
                if (candidate == null || getPackageName().equals(candidate.getPackageName())) continue;
                if (checkpointPackage != null
                        && checkpointPackage.equals(candidate.getPackageName())) {
                    checkpointCandidate = candidate;
                }
                PlaybackState state = candidate.getPlaybackState();
                if (savedCandidate == null && lastPackage != null
                        && lastPackage.equals(candidate.getPackageName())) savedCandidate = candidate;
                if (playingCandidate == null && state != null
                        && state.getState() == PlaybackState.STATE_PLAYING) playingCandidate = candidate;
                if (metadataCandidate == null && candidate.getMetadata() != null) {
                    metadataCandidate = candidate;
                }
            }
        }

        boolean checkpointIdentityMatches = checkpointCandidate != null
                && matchesSavedMediaIdentity(checkpoint, checkpointCandidate.getMetadata());
        MediaController verifiedCheckpointCandidate = checkpointIdentityMatches
                ? checkpointCandidate : null;
        boolean preserveCheckpoint = restorePending || previousBootCheckpoint;

        MediaController chosen;
        if (verifiedCheckpointCandidate != null) {
            chosen = verifiedCheckpointCandidate;
        } else if (preserveCheckpoint) {
            // After a reboot, do not let another app's early MediaSession overwrite
            // the previous car session while its source app is still starting.
            chosen = null;
        } else if (savedCandidate != null) {
            chosen = savedCandidate;
        } else if (playingCandidate != null) {
            chosen = playingCandidate;
        } else {
            chosen = metadataCandidate;
        }

        MediaController previous = activeController;
        if (previous != chosen) {
            Log.i(TAG, "Active media controller changed: "
                    + (previous == null ? "none" : previous.getPackageName())
                    + " -> " + (chosen == null ? "none" : chosen.getPackageName()));
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
                        if (selectedController == activeController) saveCurrentCheckpoint();
                    }
                    @Override public void onPlaybackStateChanged(PlaybackState state) {
                        // Some source apps auto-resume as soon as AA reconnects. If that
                        // happens before an external output is available, stop that early
                        // local-speaker burst; the restore service will issue Play again
                        // only after its output-stability gate opens.
                        boolean restorePending = getSharedPreferences(
                                RESUME_PREFS, Context.MODE_PRIVATE)
                                .getBoolean("checkpoint_restore_pending", false);
                        if (state != null && state.getState() == PlaybackState.STATE_PLAYING
                                && restorePending
                                && !MediaAutoRestoreService.isExternalOutputStable()) {
                            try {
                                selectedController.getTransportControls().pause();
                                Log.w(TAG, "Held source auto-resume until external audio output is stable: "
                                        + selectedController.getPackageName());
                            } catch (RuntimeException error) {
                                Log.w(TAG, "Could not hold early source playback during AA route handoff", error);
                            }
                        }
                        publish(selectedController);
                        if (selectedController == activeController) saveCurrentCheckpoint();
                    }
                    @Override public void onSessionDestroyed() {
                        refreshFromManager();
                    }
                };
                try { selectedController.registerCallback(controllerCallback, MAIN); }
                catch (RuntimeException error) { Log.w(TAG, "Cannot observe media controller", error); }
            }
        }
        checkpointPackageController = checkpointCandidate;
        checkpointController = verifiedCheckpointCandidate;
        publish(chosen);

        MAIN.removeCallbacks(controllerRefreshTask);
        if (chosen == null || (preserveCheckpoint && verifiedCheckpointCandidate == null)) {
            MAIN.postDelayed(controllerRefreshTask, 2000L);
        }
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
            MediaAutoRestoreService.updateMediaSession(this, latest);
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
        checkpointController = null;
        checkpointPackageController = null;
        controllerCallback = null;
        publish(null);
    }
}
