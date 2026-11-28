package com.carassistant.mediaimlang;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.List;

/**
 * Observes real third-party media sessions. A paused session with metadata is still
 * a real media app session and must retain priority over the silent fallback.
 */
public final class MediaSessionWatcherService extends NotificationListenerService {
    private static final String TAG = "MediaImLangWatcher";
    private MediaSessionManager manager;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean lastExternalSessionPresent;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean externalSessionPresent = false;
            try {
                List<MediaController> sessions = manager.getActiveSessions(
                        new ComponentName(MediaSessionWatcherService.this, MediaSessionWatcherService.class));
                for (MediaController controller : sessions) {
                    if (getPackageName().equals(controller.getPackageName())
                            || "com.carassistant.v10".equals(controller.getPackageName())
                            || "com.google.android.projection.gearhead".equals(controller.getPackageName())) continue;
                    PlaybackState state = controller.getPlaybackState();
                    if (state == null) continue;
                    int playback = state.getState();
                    boolean liveState = playback == PlaybackState.STATE_PLAYING
                            || playback == PlaybackState.STATE_PAUSED
                            || playback == PlaybackState.STATE_BUFFERING
                            || playback == PlaybackState.STATE_CONNECTING
                            || playback == PlaybackState.STATE_FAST_FORWARDING
                            || playback == PlaybackState.STATE_REWINDING;
                    MediaMetadata metadata = controller.getMetadata();
                    boolean hasMediaData = metadata != null
                            && (metadata.containsKey(MediaMetadata.METADATA_KEY_TITLE)
                            || metadata.containsKey(MediaMetadata.METADATA_KEY_ARTIST)
                            || metadata.containsKey(MediaMetadata.METADATA_KEY_MEDIA_ID));
                    if (liveState && hasMediaData) {
                        externalSessionPresent = true;
                        break;
                    }
                }
            } catch (SecurityException error) {
                Log.w(TAG, "Notification access is not granted; cannot inspect other media sessions", error);
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to inspect active media sessions", error);
            }

            if (externalSessionPresent != lastExternalSessionPresent) {
                lastExternalSessionPresent = externalSessionPresent;
                Log.i(TAG, "External media session present=" + externalSessionPresent);
                SilentMediaBrowserService.setExternalMediaSessionPresent(externalSessionPresent);
            }
            handler.postDelayed(this, 1500L);
        }
    };

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        manager = getSystemService(MediaSessionManager.class);
        lastExternalSessionPresent = false;
        handler.removeCallbacks(poll);
        // Inspect existing real sessions before allowing the silent fallback to start.
        poll.run();
        SilentMediaBrowserService.onSessionAccessReady();
        Log.i(TAG, "Notification listener connected; external MediaSession monitoring started");
    }

    @Override public void onListenerDisconnected() {
        handler.removeCallbacks(poll);
        lastExternalSessionPresent = false;
        SilentMediaBrowserService.onSessionAccessLost();
        super.onListenerDisconnected();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        // Session state, not notification churn, is the source of truth.
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(poll);
        super.onDestroy();
    }
}
