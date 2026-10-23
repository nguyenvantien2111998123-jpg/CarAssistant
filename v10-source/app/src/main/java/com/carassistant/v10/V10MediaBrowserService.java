package com.carassistant.v10;

import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.MediaDescription;
import android.service.media.MediaBrowserService;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * Publishes CarAsistant's media session to Android Auto only while the active
 * V10 projection target matches the package that owns the real media session.
 * Transport commands are forwarded to the original app's MediaController;
 * this service never creates or plays a second audio stream.
 */
public final class V10MediaBrowserService extends MediaBrowserService {
    private static final String TAG = "V10MediaBrowser";
    private static final String ROOT_ID = "carassistant_media_root";
    private static volatile V10MediaBrowserService instance;

    private MediaSession mediaSession;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mediaSession = new MediaSession(this, TAG);
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        // Route Android Auto now-playing card taps back through V10.
        Intent openV10 = new Intent(this, V10ControlReceiver.class)
                .setAction(V10ControlReceiver.ACTION_MEDIA_CONTROLLER_OPEN);
        PendingIntent openV10PendingIntent = PendingIntent.getBroadcast(
                this, 710, openV10,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mediaSession.setSessionActivity(openV10PendingIntent);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MediaSessionBridge.play(); }
            @Override public void onPause() { MediaSessionBridge.pause(); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
                if ("open_v10".equals(mediaId)) {
                    requestV10Open();
                    return;
                }
                MediaSessionBridge.play();
            }
            @Override public void onSkipToNext() { MediaSessionBridge.skipNext(); }
            @Override public void onSkipToPrevious() { MediaSessionBridge.skipPrevious(); }
            @Override public void onSeekTo(long position) {
                MediaSessionBridge.seekTo(position);
            }
        });
        setSessionToken(mediaSession.getSessionToken());
        refreshFromSnapshot();
        Log.i(TAG, "Media browser service created with V10 session click route");
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        // Android Auto connects as Gearhead; allow it and Android's own same-app
        // clients without exposing a browse catalog to unrelated applications.
        if (!"com.google.android.projection.gearhead".equals(clientPackageName)
                && !getPackageName().equals(clientPackageName)) {
            Log.d(TAG, "Rejecting media browser client " + clientPackageName);
            return null;
        }
        refreshFromSnapshot();
        Log.i(TAG, "Android Auto connected; sessionActivity routes taps to V10");
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        // Offer one explicit entry into the existing V10 UI instead of an empty
        // Controller browse screen or a duplicate media catalog.
        MediaDescription openV10 = new MediaDescription.Builder()
                .setMediaId("open_v10")
                .setTitle("Open CarAsistant V10")
                .setSubtitle("Open on car display")
                .build();
        result.sendResult(Collections.singletonList(
                new MediaBrowser.MediaItem(openV10, MediaBrowser.MediaItem.FLAG_PLAYABLE)));
    }

    private void requestV10Open() {
        Intent open = new Intent(this, V10ControlReceiver.class)
                .setAction(V10ControlReceiver.ACTION_MEDIA_CONTROLLER_OPEN);
        sendBroadcast(open);
        Log.i(TAG, "Controller browse item selected; requested V10 on car display");
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    public static void refreshIfRunning(Context context) {
        V10MediaBrowserService service = instance;
        if (service != null) service.refreshFromSnapshot();
    }

    static boolean isTargetMedia(String targetPackage, String mediaPackage) {
        return targetPackage != null && !targetPackage.isEmpty()
                && mediaPackage != null && !mediaPackage.isEmpty()
                && targetPackage.equals(mediaPackage);
    }

    private void refreshFromSnapshot() {
        if (mediaSession == null) return;
        ComponentName target = V10SessionStore.getTarget(this);
        MediaSessionBridge.Snapshot snapshot = MediaSessionBridge.getSnapshot();
        boolean matches = snapshot != null
                && isTargetMedia(target == null ? null : target.getPackageName(), snapshot.packageName);

        if (!matches) {
            mediaSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "")
                    .build());
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_NONE, 0L, 0f)
                    .setActions(0L)
                    .build());
            mediaSession.setActive(false);
            return;
        }

        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, safe(snapshot.title))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, safe(snapshot.artist))
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, safe(snapshot.title))
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, safe(snapshot.artist));
        if (snapshot.duration > 0L) {
            metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, snapshot.duration);
        }
        Bitmap artwork = snapshot.artwork;
        if (artwork != null) {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
            metadata.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork);
        }
        mediaSession.setMetadata(metadata.build());

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
        PlaybackState state = new PlaybackState.Builder()
                .setActions(actions)
                .setState(snapshot.playing ? PlaybackState.STATE_PLAYING
                                : PlaybackState.STATE_PAUSED,
                        Math.max(0L, snapshot.position), snapshot.playing ? 1f : 0f)
                .build();
        mediaSession.setPlaybackState(state);
        mediaSession.setActive(true);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
