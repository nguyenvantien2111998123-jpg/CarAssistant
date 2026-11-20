package com.carassistant.v10;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;

/**
 * Plays the silent production pulse or the user-selected audible phone-test MP4.
 * It deliberately owns no MediaSession: MediaSessionBridge remains the sole owner
 * of the CarAsistant mirror session.
 */
final class MediaFallbackPlayer {
    interface PlaybackListener {
        void onFallbackPlaybackStateChanged(boolean playing);
        default void onFallbackError(String message) { }
    }

    private static final String TAG = "CarAssistantMedia";
    private static final long PRODUCTION_PLAY_WINDOW_MS = 350L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final PlaybackListener listener;
    private MediaPlayer player;
    private ToneGenerator testTone;
    private Runnable pauseTask;

    MediaFallbackPlayer(PlaybackListener listener) {
        this.listener = listener;
    }

    void start(Context context) {
        start(context, false, null);
    }

    void start(Context context, boolean phoneTestMode, Uri testVideoUri) {
        if (player != null) return;
        if (phoneTestMode && testVideoUri == null) {
            reportError("Chưa chọn video test MP4. Hãy chọn 1000003919.mp4 trước khi bật giám sát.");
            return;
        }

        MediaPlayer candidate = new MediaPlayer();
        try {
            candidate.setAudioStreamType(AudioManager.STREAM_MUSIC);
            if (phoneTestMode) {
                candidate.setDataSource(context, testVideoUri);
            } else {
                try (AssetFileDescriptor descriptor =
                             context.getResources().openRawResourceFd(R.raw.media_fallback)) {
                    if (descriptor == null) {
                        candidate.release();
                        reportError("Không tìm thấy tài nguyên res/raw/media_fallback.");
                        return;
                    }
                    candidate.setDataSource(descriptor.getFileDescriptor(),
                            descriptor.getStartOffset(), descriptor.getLength());
                }
            }

            player = candidate;
            candidate.setOnPreparedListener(prepared -> {
                if (player != prepared) {
                    prepared.release();
                    return;
                }
                try {
                    // The phone test keeps playing until a valid external session appears.
                    // Production retains the original short silent pulse.
                    if (phoneTestMode) prepared.setLooping(true);
                    prepared.start();
                    if (phoneTestMode) {
                        try {
                            testTone = new ToneGenerator(AudioManager.STREAM_MUSIC, 85);
                            testTone.startTone(ToneGenerator.TONE_PROP_BEEP, 450);
                        } catch (RuntimeException toneError) {
                            Log.w(TAG, "Could not play audible test marker", toneError);
                        }
                    }
                    if (listener != null) listener.onFallbackPlaybackStateChanged(true);
                    Log.i(TAG, phoneTestMode
                            ? "Phone test MP4 started from selected document URI; looping until source session returns"
                            : "Silent production fallback MP4 started");

                    if (!phoneTestMode) {
                        pauseTask = () -> {
                            if (player != prepared) return;
                            try {
                                if (prepared.isPlaying()) prepared.pause();
                                if (listener != null) listener.onFallbackPlaybackStateChanged(false);
                                Log.i(TAG, "Silent production fallback MP4 paused; mirror metadata untouched");
                            } catch (IllegalStateException error) {
                                Log.w(TAG, "Could not pause fallback MP4", error);
                                stop();
                            }
                        };
                        main.postDelayed(pauseTask, PRODUCTION_PLAY_WINDOW_MS);
                    }
                } catch (IllegalStateException error) {
                    reportError("Không thể bắt đầu phát MP4: " + error.getMessage());
                    stop();
                }
            });
            candidate.setOnCompletionListener(completed -> {
                if (player == completed && listener != null) {
                    listener.onFallbackPlaybackStateChanged(false);
                }
            });
            candidate.setOnErrorListener((failed, what, extra) -> {
                reportError("MediaPlayer lỗi khi đọc MP4 (what=" + what + ", extra=" + extra + ").");
                stop();
                return true;
            });
            candidate.prepareAsync();
            Log.i(TAG, phoneTestMode
                    ? "Preparing selected phone-test MP4: " + testVideoUri
                    : "Preparing production fallback MP4");
        } catch (IOException | RuntimeException error) {
            if (player == candidate) player = null;
            try { candidate.release(); } catch (RuntimeException ignored) { }
            reportError("Không mở được MP4: " + error.getMessage());
        }
    }

    private void reportError(String message) {
        Log.e(TAG, message);
        if (listener != null) listener.onFallbackError(message);
    }

    void stop() {
        if (pauseTask != null) {
            main.removeCallbacks(pauseTask);
            pauseTask = null;
        }
        if (testTone != null) {
            try { testTone.release(); } catch (RuntimeException ignored) { }
            testTone = null;
        }
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
    }
}
