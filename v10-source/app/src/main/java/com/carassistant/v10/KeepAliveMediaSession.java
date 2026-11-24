package com.carassistant.v10;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.util.Log;

/** Phone-only keepalive session, explicitly excluded from Gearhead's session lists. */
final class KeepAliveMediaSession {
    static final String EXTRA_KEEPALIVE = "com.carassistant.v10.internal.KEEPALIVE_SESSION";
    private static final String TAG = "CarAssistantMedia";
    private final Context context;
    private final MediaSession session;
    private MediaFallbackPlayer player;
    private boolean connected;
    private boolean externalPlaying;

    KeepAliveMediaSession(Context context) {
        this.context = context.getApplicationContext();
        session = new MediaSession(this.context, "CarAsistant Phone KeepAlive");
        Bundle extras = new Bundle();
        extras.putBoolean(EXTRA_KEEPALIVE, true);
        session.setExtras(extras);
        session.setCallback(new MediaSession.Callback() { });
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "CarAsistant KeepAlive")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "CarAsistant")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Internal session")
                .build());
        session.setPlaybackState(pausedState());
    }

    void update(boolean isConnected, boolean hasExternalPlayingSession) {
        connected = isConnected;
        externalPlaying = hasExternalPlayingSession;
        if (!connected) {
            stopPlayer();
            session.setPlaybackState(pausedState());
            session.setActive(false);
            Log.i(TAG, "Phone keepalive MediaSession deactivated: Android Auto disconnected");
            return;
        }
        session.setActive(true);
        if (externalPlaying) {
            stopPlayer();
            session.setPlaybackState(pausedState());
            Log.i(TAG, "Phone keepalive idle: external media is playing");
            return;
        }
        if (player == null) {
            player = new MediaFallbackPlayer(playing -> {
                if (!connected || externalPlaying) return;
                session.setPlaybackState(playing ? playingState() : pausedState());
                session.setActive(true);
                Log.i(TAG, "Phone keepalive video state=" + (playing ? "PLAYING" : "PAUSED"));
            });
            Log.i(TAG, "Starting silent MP4 for phone keepalive session");
            player.start(context);
        } else {
            session.setPlaybackState(pausedState());
            session.setActive(true);
        }
    }

    private PlaybackState pausedState() {
        return new PlaybackState.Builder().setActions(0L)
                .setState(PlaybackState.STATE_PAUSED, 0L, 0f).build();
    }

    private PlaybackState playingState() {
        return new PlaybackState.Builder().setActions(0L)
                .setState(PlaybackState.STATE_PLAYING, 0L, 0f).build();
    }

    private void stopPlayer() {
        if (player != null) {
            player.stop();
            player = null;
        }
    }

    void release() {
        connected = false;
        stopPlayer();
        try {
            session.setActive(false);
            session.release();
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not release phone keepalive session", error);
        }
    }
}
