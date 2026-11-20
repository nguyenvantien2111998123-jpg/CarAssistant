package com.carassistant.v10;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;

/**
 * Silent, video-only fallback player. It deliberately owns no MediaSession:
 * MediaSessionBridge remains the sole owner of the CarAsistant mirror session.
 */
final class MediaFallbackPlayer {
    interface PlaybackListener {
        void onFallbackPlaybackStateChanged(boolean playing);
    }

    private static final String TAG = "CarAssistantMedia";
    private static final long PLAY_WINDOW_MS = 350L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final PlaybackListener listener;
    private MediaPlayer player;
    private ToneGenerator testTone;
    private Runnable pauseTask;

    MediaFallbackPlayer(PlaybackListener listener) {
        this.listener = listener;
    }

    void start(Context context) { start(context, false); }

    void start(Context context, boolean audibleTest) {
        if (player != null) return;

        MediaPlayer candidate = new MediaPlayer();
        try (AssetFileDescriptor descriptor =
                     context.getResources().openRawResourceFd(R.raw.media_fallback)) {
            if (descriptor == null) {
                candidate.release();
                Log.e(TAG, "Fallback MP4 resource is unavailable");
                return;
            }
            candidate.setDataSource(descriptor.getFileDescriptor(),
                    descriptor.getStartOffset(), descriptor.getLength());
            player = candidate;
            candidate.setOnPreparedListener(prepared -> {
                if (player != prepared) {
                    prepared.release();
                    return;
                }
                try {
                    prepared.start();
                    if (audibleTest) {
                        try {
                            testTone = new ToneGenerator(AudioManager.STREAM_MUSIC, 85);
                            testTone.startTone(ToneGenerator.TONE_PROP_BEEP, 450);
                        } catch (RuntimeException toneError) {
                            Log.w(TAG, "Could not play audible test marker", toneError);
                        }
                    }
                    if (listener != null) listener.onFallbackPlaybackStateChanged(true);
                    Log.i(TAG, audibleTest ? "Phone test MP4 started with audible marker" : "Silent fallback MP4 started");
                    pauseTask = () -> {
                        if (player != prepared) return;
                        try {
                            if (prepared.isPlaying()) prepared.pause();
                            if (listener != null) listener.onFallbackPlaybackStateChanged(false);
                            Log.i(TAG, "Silent fallback MP4 paused; mirror metadata untouched");
                        } catch (IllegalStateException error) {
                            Log.w(TAG, "Could not pause fallback MP4", error);
                            stop();
                        }
                    };
                    main.postDelayed(pauseTask, PLAY_WINDOW_MS);
                } catch (IllegalStateException error) {
                    Log.w(TAG, "Could not start fallback MP4", error);
                    if (listener != null) listener.onFallbackPlaybackStateChanged(false);
                    stop();
                }
            });
            candidate.setOnCompletionListener(completed -> {
                if (player == completed) {
                    Log.i(TAG, "Fallback MP4 reached end before pause window");
                }
            });
            candidate.setOnErrorListener((failed, what, extra) -> {
                Log.e(TAG, "Fallback MP4 playback error what=" + what + " extra=" + extra);
                if (listener != null) listener.onFallbackPlaybackStateChanged(false);
                stop();
                return true;
            });
            candidate.prepareAsync();
        } catch (IOException | RuntimeException error) {
            if (player == candidate) player = null;
            try { candidate.release(); } catch (RuntimeException ignored) { }
            Log.e(TAG, "Could not prepare fallback MP4", error);
        }
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
