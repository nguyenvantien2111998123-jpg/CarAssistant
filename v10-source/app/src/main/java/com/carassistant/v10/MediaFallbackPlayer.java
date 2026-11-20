package com.carassistant.v10;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.IOException;

/**
 * Plays the bundled silent MP4 in a loop while the integrated CarAsistant
 * MediaSession is the only published fallback session.
 *
 * This player does not request audio focus, publish metadata, or create a
 * MediaSession. Audio output is explicitly muted as an additional safeguard.
 */
final class MediaFallbackPlayer {
    interface PlaybackListener {
        void onFallbackPlaybackStateChanged(boolean playing);
        default void onFallbackError(String message) { }
    }

    private static final String TAG = "CarAssistantMedia";

    private final PlaybackListener listener;
    private MediaPlayer player;

    MediaFallbackPlayer(PlaybackListener listener) {
        this.listener = listener;
    }

    boolean isActive() {
        return player != null;
    }

    void start(Context context) {
        if (player != null) return;

        MediaPlayer candidate = new MediaPlayer();
        try {
            candidate.setAudioStreamType(AudioManager.STREAM_MUSIC);
            candidate.setVolume(0f, 0f);
            try (AssetFileDescriptor descriptor =
                         context.getResources().openRawResourceFd(R.raw.media_fallback)) {
                if (descriptor == null) {
                    candidate.release();
                    reportError("Không tìm thấy tài nguyên res/raw/media_fallback.mp4.");
                    return;
                }
                candidate.setDataSource(descriptor.getFileDescriptor(),
                        descriptor.getStartOffset(), descriptor.getLength());
            }

            player = candidate;
            candidate.setOnPreparedListener(prepared -> {
                if (player != prepared) {
                    prepared.release();
                    return;
                }
                try {
                    prepared.setVolume(0f, 0f);
                    prepared.setLooping(true);
                    prepared.start();
                    if (listener != null) listener.onFallbackPlaybackStateChanged(true);
                    Log.i(TAG, "Bundled silent fallback MP4 started; muted and looping without audio focus");
                } catch (IllegalStateException error) {
                    reportError("Không thể bắt đầu phát MP4 dự phòng: " + error.getMessage());
                    stop();
                }
            });
            candidate.setOnErrorListener((failed, what, extra) -> {
                reportError("MediaPlayer lỗi khi đọc MP4 dự phòng (what="
                        + what + ", extra=" + extra + ").");
                stop();
                return true;
            });
            candidate.prepareAsync();
            Log.i(TAG, "Preparing bundled silent fallback MP4");
        } catch (IOException | RuntimeException error) {
            if (player == candidate) player = null;
            try { candidate.release(); } catch (RuntimeException ignored) { }
            reportError("Không mở được MP4 dự phòng: " + error.getMessage());
        }
    }

    private void reportError(String message) {
        Log.e(TAG, message);
        if (listener != null) listener.onFallbackError(message);
    }

    void stop() {
        MediaPlayer current = player;
        player = null;
        if (current != null) {
            try {
                current.setOnPreparedListener(null);
                current.setOnCompletionListener(null);
                current.setOnErrorListener(null);
                current.release();
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not release fallback MP4 player", error);
            }
        }
        if (listener != null) listener.onFallbackPlaybackStateChanged(false);
    }
}
