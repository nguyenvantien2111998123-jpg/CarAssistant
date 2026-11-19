package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import android.service.notification.NotificationListenerService;

import java.util.List;

/**
 * Optional, isolated fallback MediaSession.
 *
 * This service owns its own MediaSession and never changes the CarAsistant
 * mirror session, active media controller, autoplay checkpoint, or steering
 * wheel handler. It is enabled separately from the phone-side Media Dự Phòng
 * screen and becomes active only when no valid third-party media session exists.
 */
public final class MediaFallbackSessionService extends NotificationListenerService {
    private static final String TAG = "CarAsistantFallback";
    private static final String PREFS = "media_fallback_session";
    private static final String KEY_ENABLED = "enabled";
    private static final long START_GRACE_MS = 1000L;
    private static final long PLAY_PULSE_MS = 350L;

    private static volatile MediaFallbackSessionService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessionManager;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private MediaSession fallbackSession;
    private boolean fallbackActive;
    private boolean destroyed;

    private final Runnable startFallbackTask = this::startFallbackIfStillNeeded;
    private final Runnable pausePulseTask = () -> {
        if (destroyed || !isEnabled(this) || !fallbackActive || fallbackSession == null) return;
        if (hasValidExternalSession()) {
            deactivateFallback();
            return;
        }
        publishState(PlaybackState.STATE_PAUSED);
        Log.i(TAG, "Fallback session pulse completed: PLAYING -> PAUSED");
    };

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply();
        MediaFallbackSessionService current = instance;
        if (current != null) {
            current.main.post(current::reconcile);
        }
    }

    public static boolean hasAccess(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(enabled)) return false;
        String expected = new ComponentName(context, MediaFallbackSessionService.class)
                .flattenToString();
        for (String item : enabled.split(":")) {
            ComponentName parsed = ComponentName.unflattenFromString(item);
            if (parsed != null && expected.equals(parsed.flattenToString())) return true;
        }
        return false;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        destroyed = false;
        instance = this;
        sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        ensureFallbackSession();
        if (sessionManager != null) {
            sessionsListener = controllers -> main.post(this::reconcile);
            try {
                sessionManager.addOnActiveSessionsChangedListener(
                        sessionsListener,
                        new ComponentName(this, MediaFallbackSessionService.class),
                        main);
            } catch (RuntimeException error) {
                Log.e(TAG, "Could not observe active media sessions", error);
            }
        }
        Log.i(TAG, "Notification listener connected; enabled=" + isEnabled(this));
        main.post(this::reconcile);
    }

    @Override
    public void onListenerDisconnected() {
        destroyed = true;
        main.removeCallbacks(startFallbackTask);
        main.removeCallbacks(pausePulseTask);
        if (sessionManager != null && sessionsListener != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (RuntimeException ignored) {
            }
        }
        sessionsListener = null;
        sessionManager = null;
        deactivateFallback();
        if (instance == this) instance = null;
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        main.removeCallbacks(startFallbackTask);
        main.removeCallbacks(pausePulseTask);
        deactivateFallback();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private void ensureFallbackSession() {
        if (fallbackSession != null) return;
        fallbackSession = new MediaSession(this, "CarAsistant Media Du Phong");
        fallbackSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        fallbackSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                Log.i(TAG, "Android requested Play on fallback session");
                main.post(() -> {
                    if (isEnabled(MediaFallbackSessionService.this)
                            && !hasValidExternalSession()) pulseFallback();
                });
            }

            @Override public void onPause() {
                Log.i(TAG, "Android requested Pause on fallback session");
                main.post(() -> {
                    if (fallbackActive) publishState(PlaybackState.STATE_PAUSED);
                });
            }

            @Override public void onStop() {
                main.post(() -> {
                    if (fallbackActive) publishState(PlaybackState.STATE_PAUSED);
                });
            }

            @Override public void onSkipToNext() {
                Log.i(TAG, "Fallback session received NEXT");
            }

            @Override public void onSkipToPrevious() {
                Log.i(TAG, "Fallback session received PREVIOUS");
            }
        });
        fallbackSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Media Dự Phòng")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "CarAsistant")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Phiên media dự phòng")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 60000L)
                .build());
        fallbackSession.setPlaybackState(buildState(PlaybackState.STATE_NONE));
    }

    private void reconcile() {
        if (destroyed || fallbackSession == null) return;
        if (!isEnabled(this)) {
            main.removeCallbacks(startFallbackTask);
            main.removeCallbacks(pausePulseTask);
            deactivateFallback();
            return;
        }

        if (hasValidExternalSession()) {
            main.removeCallbacks(startFallbackTask);
            main.removeCallbacks(pausePulseTask);
            deactivateFallback();
            return;
        }

        if (!fallbackActive) {
            main.removeCallbacks(startFallbackTask);
            main.postDelayed(startFallbackTask, START_GRACE_MS);
        }
    }

    private void startFallbackIfStillNeeded() {
        if (destroyed || !isEnabled(this) || fallbackSession == null) return;
        if (hasValidExternalSession()) {
            deactivateFallback();
            return;
        }
        if (!fallbackActive) {
            fallbackSession.setActive(true);
            fallbackActive = true;
            Log.i(TAG, "No valid external MediaSession; activating fallback session");
        }
        pulseFallback();
    }

    private void pulseFallback() {
        if (destroyed || !isEnabled(this) || !fallbackActive || fallbackSession == null) return;
        main.removeCallbacks(pausePulseTask);
        if (hasValidExternalSession()) {
            deactivateFallback();
            return;
        }
        publishState(PlaybackState.STATE_PLAYING);
        Log.i(TAG, "Fallback session pulse started");
        main.postDelayed(pausePulseTask, PLAY_PULSE_MS);
    }

    private boolean hasValidExternalSession() {
        if (sessionManager == null) return false;
        List<MediaController> controllers;
        try {
            controllers = sessionManager.getActiveSessions(
                    new ComponentName(this, MediaFallbackSessionService.class));
        } catch (SecurityException | RuntimeException error) {
            Log.w(TAG, "Cannot inspect active sessions yet", error);
            return false;
        }
        if (controllers == null) return false;
        for (MediaController controller : controllers) {
            if (controller == null) continue;
            String packageName = controller.getPackageName();
            // Ignore every CarAsistant-owned session, including the existing AA mirror.
            if (TextUtils.isEmpty(packageName) || getPackageName().equals(packageName)) continue;
            if (isValidExternalSession(controller)) {
                Log.d(TAG, "External media session found: " + packageName);
                return true;
            }
        }
        return false;
    }

    private boolean isValidExternalSession(MediaController controller) {
        PlaybackState state;
        MediaMetadata metadata;
        try {
            state = controller.getPlaybackState();
            metadata = controller.getMetadata();
        } catch (RuntimeException error) {
            return false;
        }
        if (state == null) {
            return metadata != null && metadata.size() > 0;
        }
        int value = state.getState();
        if (value == PlaybackState.STATE_PLAYING
                || value == PlaybackState.STATE_BUFFERING
                || value == PlaybackState.STATE_CONNECTING
                || value == PlaybackState.STATE_FAST_FORWARDING
                || value == PlaybackState.STATE_REWINDING
                || value == PlaybackState.STATE_SKIPPING_TO_NEXT
                || value == PlaybackState.STATE_SKIPPING_TO_PREVIOUS) {
            return true;
        }
        if (value == PlaybackState.STATE_PAUSED) {
            // A paused player with track metadata or transport actions is still a real session.
            return (metadata != null && metadata.size() > 0) || state.getActions() != 0L;
        }
        return false;
    }

    private void deactivateFallback() {
        if (fallbackSession == null) return;
        main.removeCallbacks(pausePulseTask);
        if (fallbackActive) {
            try {
                fallbackSession.setPlaybackState(buildState(PlaybackState.STATE_NONE));
                fallbackSession.setActive(false);
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not deactivate fallback MediaSession", error);
            }
            fallbackActive = false;
            Log.i(TAG, "Fallback MediaSession deactivated; external media has priority");
        }
    }

    private void publishState(int state) {
        if (fallbackSession == null || !fallbackActive) return;
        try {
            fallbackSession.setPlaybackState(buildState(state));
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not publish fallback playback state", error);
        }
    }

    private PlaybackState buildState(int state) {
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        return new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, 0L, 1.0f)
                .build();
    }
}
