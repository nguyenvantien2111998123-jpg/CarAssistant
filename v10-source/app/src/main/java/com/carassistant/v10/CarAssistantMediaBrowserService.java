package com.carassistant.v10;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Android Auto native media endpoint. It mirrors the selected phone player's
 * metadata and forwards transport commands through the existing MediaSessionBridge.
 * This service does not create a second audio player.
 */
public final class CarAssistantMediaBrowserService extends MediaBrowserServiceCompat
        implements MediaSessionBridge.Listener {
    private static final String TAG = "CarAssistantMedia";
    private static final String ROOT_ID = "carassistant_root";
    private static final String ITEM_ID = "carassistant_active_media";
    private static final long SEEK_STEP_MS = 15_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MediaSessionCompat mediaSession;
    private volatile MediaSessionBridge.Snapshot latest = MediaSessionBridge.getSnapshot();

    @Override
    public void onCreate() {
        super.onCreate();
        Intent openIntent = new Intent(this, MediaSessionEntryActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent sessionActivity = PendingIntent.getActivity(
                this, 311, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        mediaSession = new MediaSessionCompat(this, TAG);
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setSessionActivity(sessionActivity);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { MediaSessionBridge.play(); }
            @Override public void onPause() { MediaSessionBridge.pause(); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
                if (ITEM_ID.equals(mediaId)) MediaSessionBridge.play();
            }
            @Override public void onPlayFromSearch(String query, Bundle extras) {
                MediaSessionBridge.play();
            }
            @Override public void onSkipToNext() { MediaSessionBridge.skipNext(); }
            @Override public void onSkipToPrevious() { MediaSessionBridge.skipPrevious(); }
            @Override public void onFastForward() { MediaSessionBridge.seekBy(SEEK_STEP_MS); }
            @Override public void onRewind() { MediaSessionBridge.seekBy(-SEEK_STEP_MS); }
            @Override public void onSeekTo(long position) { seekAbsolute(position); }
            @Override public void onStop() { MediaSessionBridge.pause(); }
        });
        setSessionToken(mediaSession.getSessionToken());
        MediaSessionBridge.addListener(this);
        updateSession(latest);
    }

    @Override
    public void onDestroy() {
        MediaSessionBridge.removeListener(this);
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.release();
            } catch (RuntimeException error) {
                Log.w(TAG, "Failed to release Android Auto media session", error);
            }
            mediaSession = null;
        }
        super.onDestroy();
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        // Android Auto and other legitimate media browsers can discover the service.
        // No private content or user data is exposed through the browse tree.
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowserCompat.MediaItem>> result) {
        ArrayList<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        MediaSessionBridge.Snapshot state = latest;
        if (ROOT_ID.equals(parentId) && state != null && !TextUtils.isEmpty(state.packageName)) {
            MediaDescriptionCompat.Builder description = new MediaDescriptionCompat.Builder()
                    .setMediaId(ITEM_ID)
                    .setTitle(TextUtils.isEmpty(state.title) ? "Đang phát media" : state.title)
                    .setSubtitle(TextUtils.isEmpty(state.artist) ? state.packageName : state.artist);
            Bitmap artwork = safeArtwork(state.artwork);
            if (artwork != null) description.setIconBitmap(artwork);
            items.add(new MediaBrowserCompat.MediaItem(
                    description.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        result.sendResult(items);
    }

    @Override
    public void onMediaChanged(MediaSessionBridge.Snapshot snapshot) {
        latest = snapshot;
        MAIN.post(() -> {
            if (mediaSession == null) return;
            updateSession(snapshot);
            notifyChildrenChanged(ROOT_ID);
        });
    }

    private void updateSession(MediaSessionBridge.Snapshot state) {
        if (mediaSession == null) return;
        boolean hasPlayer = state != null && !TextUtils.isEmpty(state.packageName);
        if (!hasPlayer) {
            mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "CarAsistant Media")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Chưa có media đang phát")
                    .build());
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY
                            | PlaybackStateCompat.ACTION_PAUSE
                            | PlaybackStateCompat.ACTION_PLAY_PAUSE)
                    .setState(PlaybackStateCompat.STATE_NONE, 0L, 0f)
                    .build());
            mediaSession.setActive(false);
            return;
        }

        MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, ITEM_ID)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,
                        TextUtils.isEmpty(state.title) ? "Đang phát media" : state.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,
                        TextUtils.isEmpty(state.artist) ? state.packageName : state.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, state.packageName)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, Math.max(0L, state.duration));
        Bitmap artwork = safeArtwork(state.artwork);
        if (artwork != null) {
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork);
        }
        mediaSession.setMetadata(metadata.build());

        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_FAST_FORWARD
                | PlaybackStateCompat.ACTION_REWIND
                | PlaybackStateCompat.ACTION_STOP;
        int playbackState = state.playing
                ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(playbackState, Math.max(0L, state.position),
                        state.playing ? 1f : 0f)
                .setExtras(new Bundle())
                .build());
        mediaSession.setActive(true);
    }

    private void seekAbsolute(long positionMs) {
        MediaSessionBridge.Snapshot state = latest;
        if (state == null || state.duration <= 0L) return;
        long target = Math.max(0L, Math.min(positionMs, state.duration));
        long current = Math.max(0L, state.position);
        MediaSessionBridge.seekBy(target - current);
    }

    private static Bitmap safeArtwork(Bitmap source) {
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
            Log.w(TAG, "Could not prepare media artwork", error);
            return null;
        }
    }
}
