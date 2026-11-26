package com.carassistant.mediaimlang;

import android.content.ComponentName;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.List;

public final class MediaSessionWatcherService extends NotificationListenerService {
    private static final String TAG = "MediaImLangWatcher";
    private MediaSessionManager manager;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean externalPlaying = false;
            try {
                List<MediaController> sessions = manager.getActiveSessions(
                        new ComponentName(MediaSessionWatcherService.this, MediaSessionWatcherService.class));
                for (MediaController controller : sessions) {
                    if (getPackageName().equals(controller.getPackageName())) continue;
                    android.media.session.PlaybackState state = controller.getPlaybackState();
                    if (state != null && state.getState() == android.media.session.PlaybackState.STATE_PLAYING) {
                        externalPlaying = true;
                        break;
                    }
                }
            } catch (SecurityException error) {
                Log.w(TAG, "Notification access is not granted; cannot inspect other media sessions", error);
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to inspect active media sessions", error);
            }
            SilentMediaBrowserService.setExternalMediaPlaying(externalPlaying);
            handler.postDelayed(this, 1000L);
        }
    };

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        manager = getSystemService(MediaSessionManager.class);
        handler.removeCallbacks(poll);
        SilentMediaBrowserService.onSessionAccessReady();
        handler.post(poll);
        Log.i(TAG, "Notification listener connected; external MediaSession monitoring started");
    }

    @Override public void onListenerDisconnected() {
        handler.removeCallbacks(poll);
        SilentMediaBrowserService.setExternalMediaPlaying(false);
        super.onListenerDisconnected();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        // Polling is intentionally centralized; notifications are not treated as proof of playback.
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(poll);
        super.onDestroy();
    }
}
