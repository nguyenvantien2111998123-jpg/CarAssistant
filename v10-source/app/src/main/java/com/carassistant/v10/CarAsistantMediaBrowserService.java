package com.carassistant.v10;

import android.graphics.Bitmap;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.service.media.MediaBrowserService;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Android Auto's standard media discovery endpoint.
 *
 * The service exposes one safe, playable "Now playing" entry and a dedicated
 * MediaSession. The session mirrors the phone player's metadata while transport
 * commands are routed back to the active source by MediaSessionBridge.
 */
public final class CarAsistantMediaBrowserService extends MediaBrowserService {
    private static final String TAG = "CarAsistantMediaBrowser";
    private static final String ROOT_ID = "carassistant_root";
    private static final String NOW_PLAYING_ID = "carassistant_now_playing";

    private MediaSession mediaSession;
    private SteeringWheelMediaKeyHandler steeringKeyHandler;
    private final MediaSessionBridge.Listener mediaListener = this::publishSnapshot;

    @Override
    public void onCreate() {
        super.onCreate();
        mediaSession = new MediaSession(this, "CarAsistant Android Auto Media Browser");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        steeringKeyHandler = new SteeringWheelMediaKeyHandler(
                new SteeringWheelMediaKeyHandler.Actions() {
                    @Override public void onNext() { MediaSessionBridge.skipNext(); }
                    @Override public void onPrevious() { MediaSessionBridge.skipPrevious(); }
                    @Override public void onSeekForward() { MediaSessionBridge.seekBy(15000L); }
                    @Override public void onSeekBackward() { MediaSessionBridge.seekBy(-15000L); }
                    @Override public void onVoiceAssistant() { sendVoiceAssistantRequest(); }
                });
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent intent) {
                if (steeringKeyHandler != null && steeringKeyHandler.handle(intent)) return true;
                return super.onMediaButtonEvent(intent);
            }

            @Override public void onPlay() { MediaSessionBridge.play(); }
            @Override public void onPause() { MediaSessionBridge.pause(); }
            @Override public void onStop() { MediaSessionBridge.pause(); }
            @Override public void onSkipToNext() { MediaSessionBridge.skipNext(); }
            @Override public void onSkipToPrevious() { MediaSessionBridge.skipPrevious(); }
            @Override public void onFastForward() { MediaSessionBridge.seekBy(15000L); }
            @Override public void onRewind() { MediaSessionBridge.seekBy(-15000L); }
            @Override public void onSeekTo(long position) { MediaSessionBridge.seekTo(position); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
                if (NOW_PLAYING_ID.equals(mediaId)) MediaSessionBridge.play();
            }
            @Override public void onPlayFromSearch(String query, Bundle extras) {
                MediaSessionBridge.play();
            }
            @Override public void onCustomAction(String action, Bundle extras) {
                if (MediaSessionBridge.ACTION_VOICE_ASSISTANT.equals(action)) {
                    sendVoiceAssistantRequest();
                }
            }
        });
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD
                        | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
                        | PlaybackState.ACTION_PLAY_FROM_SEARCH)
                .setState(PlaybackState.STATE_NONE, 0L, 0f)
                .build());
        mediaSession.setActive(true);
        setSessionToken(mediaSession.getSessionToken());

        // The NotificationListener-backed bridge supplies live metadata and keeps
        // its five-second durable checkpoint behavior unchanged.
        MediaSessionBridge.addListener(mediaListener);
        Log.i(TAG, "Android Auto MediaBrowserService published its MediaSession token");
    }

    private void sendVoiceAssistantRequest() {
        Intent intent = new Intent("com.carassistant.v10.STEERING_VOICE")
                .setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void publishSnapshot(MediaSessionBridge.Snapshot snapshot) {
        MediaSession session = mediaSession;
        if (session == null) return;

        MediaSessionBridge.Snapshot state = snapshot;
        MediaDescription.Builder description = new MediaDescription.Builder()
                .setMediaId(NOW_PLAYING_ID)
                .setTitle(state == null || TextUtils.isEmpty(state.title)
                        ? "CarAsistant Media" : state.title)
                .setSubtitle(state == null || TextUtils.isEmpty(state.artist)
                        ? "Ứng dụng media trên điện thoại" : state.artist);
        Bitmap artwork = state == null ? null : state.artwork;
        if (artwork != null && !artwork.isRecycled()) {
            description.setIconBitmap(artwork);
        }
        session.setMetadata(new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE,
                        state == null || TextUtils.isEmpty(state.title)
                                ? "CarAsistant Media" : state.title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST,
                        state == null || TextUtils.isEmpty(state.artist)
                                ? "Ứng dụng media trên điện thoại" : state.artist)
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM,
                        state == null ? "" : state.packageName)
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION,
                        state == null ? 0L : Math.max(0L, state.duration))
                .putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                .build());

        boolean playing = state != null && state.playing;
        long position = state == null ? 0L : Math.max(0L, state.position);
        long duration = state == null ? 0L : Math.max(0L, state.duration);
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD
                | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackState.ACTION_PLAY_FROM_SEARCH;
        PlaybackState.Builder playback = new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        duration > 0L ? Math.min(position, duration) : position,
                        playing ? 1f : 0f)
                .addCustomAction(new PlaybackState.CustomAction.Builder(
                        MediaSessionBridge.ACTION_VOICE_ASSISTANT,
                        "Trợ lý giọng nói CarAsistant",
                        android.R.drawable.ic_btn_speak_now).build());
        session.setPlaybackState(playback.build());
        session.setActive(true);
        notifyChildrenChanged(ROOT_ID);
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        Bundle extras = new Bundle();
        extras.putInt("android.media.browse.CONTENT_STYLE_SUPPORTED", 1);
        extras.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1);
        return new BrowserRoot(ROOT_ID, extras);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        if (!ROOT_ID.equals(parentId)) {
            result.sendResult(new ArrayList<>());
            return;
        }

        MediaSessionBridge.Snapshot state = MediaSessionBridge.getSnapshot();
        MediaDescription.Builder item = new MediaDescription.Builder()
                .setMediaId(NOW_PLAYING_ID)
                .setTitle(state == null || TextUtils.isEmpty(state.title)
                        ? "CarAsistant Media" : state.title)
                .setSubtitle(state == null || TextUtils.isEmpty(state.artist)
                        ? "Đang chờ ứng dụng phát media" : state.artist);
        if (state != null && state.artwork != null && !state.artwork.isRecycled()) {
            item.setIconBitmap(state.artwork);
        }
        List<MediaBrowser.MediaItem> children = new ArrayList<>();
        children.add(new MediaBrowser.MediaItem(item.build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
        result.sendResult(children);
    }

    @Override
    public void onDestroy() {
        MediaSessionBridge.removeListener(mediaListener);
        if (steeringKeyHandler != null) {
            steeringKeyHandler.dispose();
            steeringKeyHandler = null;
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }
}
