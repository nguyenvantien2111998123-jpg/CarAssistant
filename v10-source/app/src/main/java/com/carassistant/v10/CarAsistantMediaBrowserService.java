package com.carassistant.v10;

import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.browse.MediaBrowserService;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Native framework media browser for Android Auto.
 *
 * The service publishes the currently active phone media session's metadata as CarAsistant's
 * media source and forwards transport commands to MediaSessionBridge. It does not start or
 * force-stop the media app; projection/task restoration remains the responsibility of the
 * CarAsistant projection + Mini Access Engine path.
 */
public final class CarAsistantMediaBrowserService extends MediaBrowserService
        implements MediaSessionBridge.Listener {
    private static final String TAG = "CarAsstMediaBrowser";
    private static final String ROOT_ID = "carassistant_root";
    private static final String NOW_PLAYING_ID = "carassistant_now_playing";

    private MediaSession mediaSession;
    private boolean listenerRegistered;

    @Override
    public void onCreate() {
        super.onCreate();
        mediaSession = new MediaSession(this, TAG);
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MediaSessionBridge.play(); }
            @Override public void onPause() { MediaSessionBridge.pause(); }
            @Override public void onSkipToNext() { MediaSessionBridge.skipNext(); }
            @Override public void onSkipToPrevious() { MediaSessionBridge.skipPrevious(); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
                if (NOW_PLAYING_ID.equals(mediaId)) {
                    // The Android Auto host has entered CarAsistant's media surface.
                    // Actual projected task selection is handled by the in-car activity.
                    publishSnapshot(MediaSessionBridge.getSnapshot());
                }
            }
        });
        setSessionToken(mediaSession.getSessionToken());
        MediaSessionBridge.addListener(this);
        listenerRegistered = true;
        publishSnapshot(MediaSessionBridge.getSnapshot());
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        if (!ROOT_ID.equals(parentId)) {
            result.sendResult(Collections.emptyList());
            return;
        }
        MediaBrowser.MediaItem item = new MediaBrowser.MediaItem(
                new android.media.MediaDescription.Builder()
                        .setMediaId(NOW_PLAYING_ID)
                        .setTitle("Mở nội dung đang chiếu")
                        .setSubtitle("CarAsistant — màn hình xe")
                        .build(),
                MediaBrowser.MediaItem.FLAG_PLAYABLE);
        List<MediaBrowser.MediaItem> children = new ArrayList<>();
        children.add(item);
        result.sendResult(children);
    }

    @Override
    public void onMediaChanged(MediaSessionBridge.Snapshot snapshot) {
        publishSnapshot(snapshot);
    }

    private void publishSnapshot(MediaSessionBridge.Snapshot snapshot) {
        if (mediaSession == null || snapshot == null) return;
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE,
                        TextUtils.isEmpty(snapshot.title) ? "CarAsistant" : snapshot.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST,
                        TextUtils.isEmpty(snapshot.artist) ? "Đang phát media" : snapshot.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "CarAsistant")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0L, snapshot.duration));
        Bitmap artwork = snapshot.artwork;
        if (artwork != null && !artwork.isRecycled()) {
            try {
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork);
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
            } catch (RuntimeException error) {
                Log.w(TAG, "Cannot publish media artwork", error);
            }
        }
        mediaSession.setMetadata(metadata.build());

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID;
        int state = snapshot.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        PlaybackState playback = new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, Math.max(0L, snapshot.position), snapshot.playing ? 1f : 0f)
                .build();
        mediaSession.setPlaybackState(playback);
        if (!mediaSession.isActive()) mediaSession.setActive(true);
    }

    @Override
    public void onDestroy() {
        if (listenerRegistered) MediaSessionBridge.removeListener(this);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }
}
