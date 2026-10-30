package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.app.PendingIntent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Exposes CarAsistant's existing phone-media session through Android's standard
 * MediaBrowser/MediaSession contract. It is a proxy only: the real player remains
 * the active MediaController selected by MediaSessionBridge.
 */
public final class CarAssistantMediaBrowserService extends MediaBrowserServiceCompat
        implements MediaSessionBridge.Listener {
    private static final String TAG = "CarAssistantMediaProxy";
    private static final String ROOT_ID = "carassistant.media.root";
    private static final String ITEM_ID = "carassistant.media.active";
    private static final String ACTION_OPEN_CAR_APP =
            "com.carassistant.v10.action.OPEN_CAR_APP_FROM_MEDIA";
    private static final String GEARHEAD_PACKAGE =
            "com.google.android.projection.gearhead";
    private static final long SEEK_STEP_MS = 15_000L;
    private static final int ARTWORK_MAX_EDGE = 384;

    private MediaSessionCompat session;
    private boolean listenerRegistered;

    @Override
    public void onCreate() {
        super.onCreate();
        Intent openIntent = new Intent(this, MediaSessionEntryActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent sessionActivity = PendingIntent.getActivity(
                this, 4107, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        session = new MediaSessionCompat(this, TAG);
        session.setSessionActivity(sessionActivity);
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { MediaSessionBridge.play(); }
            @Override public void onPause() { MediaSessionBridge.pause(); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
                if (ITEM_ID.equals(mediaId)) {
                    // Selecting the now-playing item on the CarAsistant Media browse page
                    // is the user's explicit request to return to the V10 Car App.
                    MediaSessionBridge.play();
                    requestCarAppOnGearhead();
                }
            }
            @Override public void onSkipToNext() { MediaSessionBridge.skipNext(); }
            @Override public void onSkipToPrevious() { MediaSessionBridge.skipPrevious(); }
            @Override public void onFastForward() { MediaSessionBridge.seekBy(SEEK_STEP_MS); }
            @Override public void onRewind() { MediaSessionBridge.seekBy(-SEEK_STEP_MS); }
            @Override public void onCustomAction(String action, Bundle extras) {
                if ("com.carassistant.v10.MEDIA_REWIND_15".equals(action)) {
                    MediaSessionBridge.seekBy(-SEEK_STEP_MS);
                } else if ("com.carassistant.v10.MEDIA_FORWARD_15".equals(action)) {
                    MediaSessionBridge.seekBy(SEEK_STEP_MS);
                }
            }
            @Override public void onSeekTo(long position) {
                MediaSessionBridge.seekTo(position);
            }
        });
        setSessionToken(session.getSessionToken());
        session.setActive(true);
        MediaSessionBridge.addListener(this);
        listenerRegistered = true;
        publish(MediaSessionBridge.getSnapshot());
    }

    private void requestCarAppOnGearhead() {
        try {
            Intent request = new Intent(ACTION_OPEN_CAR_APP);
            request.setPackage(GEARHEAD_PACKAGE);
            sendBroadcast(request);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not request CarAsistant V10 from Gearhead", error);
        }
    }

    @Override
    public void onDestroy() {
        if (listenerRegistered) MediaSessionBridge.removeListener(this);
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public void onMediaChanged(MediaSessionBridge.Snapshot snapshot) {
        publish(snapshot);
        notifyChildrenChanged(ROOT_ID);
    }

    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid,
                                 @Nullable Bundle rootHints) {
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId,
                               @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        if (!ROOT_ID.equals(parentId)) {
            result.sendResult(new ArrayList<>());
            return;
        }
        MediaSessionBridge.Snapshot snapshot = MediaSessionBridge.getSnapshot();
        ArrayList<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        if (snapshot != null && snapshot.packageName != null && !snapshot.packageName.isEmpty()) {
            MediaDescriptionCompat.Builder description = new MediaDescriptionCompat.Builder()
                    .setMediaId(ITEM_ID)
                    .setTitle(nonEmpty(snapshot.title, "Đang phát media"))
                    .setSubtitle(nonEmpty(snapshot.artist, snapshot.packageName))
                    .setDescription(snapshot.packageName);
            Bitmap art = safeArtwork(snapshot.artwork);
            if (art != null) description.setIconBitmap(art);
            items.add(new MediaBrowserCompat.MediaItem(
                    description.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        result.sendResult(items);
    }

    private void publish(MediaSessionBridge.Snapshot snapshot) {
        if (session == null) return;
        try {
            boolean hasMedia = snapshot != null && snapshot.packageName != null
                    && !snapshot.packageName.isEmpty();
            MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder();
            if (hasMedia) {
                metadata.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, ITEM_ID);
                metadata.putString(MediaMetadataCompat.METADATA_KEY_TITLE,
                        nonEmpty(snapshot.title, "Đang phát media"));
                metadata.putString(MediaMetadataCompat.METADATA_KEY_ARTIST,
                        nonEmpty(snapshot.artist, snapshot.packageName));
                metadata.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE,
                        nonEmpty(snapshot.title, "Đang phát media"));
                metadata.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                        nonEmpty(snapshot.artist, snapshot.packageName));
                metadata.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION,
                        snapshot.packageName);
                if (snapshot.duration > 0) {
                    metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, snapshot.duration);
                }
                Bitmap art = safeArtwork(snapshot.artwork);
                if (art != null) {
                    metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
                    metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art);
                }
            }
            session.setMetadata(metadata.build());

            long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackStateCompat.ACTION_FAST_FORWARD
                    | PlaybackStateCompat.ACTION_REWIND
                    | PlaybackStateCompat.ACTION_SEEK_TO
                    | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID;
            int state = !hasMedia ? PlaybackStateCompat.STATE_NONE
                    : (snapshot.playing ? PlaybackStateCompat.STATE_PLAYING
                    : PlaybackStateCompat.STATE_PAUSED);
            float speed = hasMedia && snapshot.playing ? 1.0f : 0.0f;
            long position = hasMedia ? Math.max(0L, snapshot.position) : 0L;
            PlaybackStateCompat.Builder playback = new PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(state, position, speed, SystemClock.elapsedRealtime());
            // Keep the compact AA widget's standard three slots intact. Expose
            // explicit ±15-second actions to controllers that support expanded/custom actions.
            playback.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                    "com.carassistant.v10.MEDIA_REWIND_15", "Lùi 15 giây",
                    android.R.drawable.ic_media_rew).build());
            playback.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                    "com.carassistant.v10.MEDIA_FORWARD_15", "Tiến 15 giây",
                    android.R.drawable.ic_media_ff).build());
            session.setPlaybackState(playback.build());
            Log.i(TAG, "Published media session: available=" + hasMedia
                    + ", state=" + state + ", title="
                    + (hasMedia ? nonEmpty(snapshot.title, "Đang phát media") : "<none>"));
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not publish active media session", error);
        }
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    /** Avoid large Binder transactions from high-resolution source artwork. */
    private static Bitmap safeArtwork(Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        try {
            int width = source.getWidth();
            int height = source.getHeight();
            int max = Math.max(width, height);
            if (max <= ARTWORK_MAX_EDGE) return source;
            float scale = (float) ARTWORK_MAX_EDGE / (float) max;
            int outWidth = Math.max(1, Math.round(width * scale));
            int outHeight = Math.max(1, Math.round(height * scale));
            Bitmap scaled = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(scaled);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(source, null, new Rect(0, 0, outWidth, outHeight), paint);
            return scaled;
        } catch (RuntimeException error) {
            Log.d(TAG, "Artwork omitted to keep media session available", error);
            return null;
        }
    }
}
