package com.carassistant.v10;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Phone-side silent PCM output used only to keep a real phone playback pipeline
 * alive while Android Auto is connected and no external media session exists.
 *
 * This class deliberately does not request audio focus and never creates a
 * MediaSession. MediaSessionBridge owns the single published session.
 */
final class SilentAudioTrackFallback {
    private static final String TAG = "CarAssistantSilentAudio";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHUNK_SAMPLES = 2048;
    private static final int MAX_DEAD_OBJECT_RECOVERIES = 3;

    private final Runnable failureListener;
    private volatile boolean running;
    private volatile AudioTrack audioTrack;
    private Thread writerThread;
    private long generation;

    SilentAudioTrackFallback(Runnable failureListener) {
        this.failureListener = failureListener;
    }

    synchronized boolean start() {
        if (isPlaying()) return true;
        stop();
        AudioTrack created = createTrack();
        if (created == null) return false;
        try {
            created.play();
            if (created.getState() != AudioTrack.STATE_INITIALIZED
                    || created.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                created.release();
                Log.w(TAG, "AudioTrack did not enter PLAYING state");
                return false;
            }
        } catch (RuntimeException error) {
            try { created.release(); } catch (RuntimeException ignored) { }
            Log.w(TAG, "Could not start silent AudioTrack", error);
            return false;
        }

        audioTrack = created;
        running = true;
        long writerGeneration = ++generation;
        writerThread = new Thread(() -> writeSilence(writerGeneration),
                "CarAsistant-SilentPCM");
        writerThread.setPriority(Thread.NORM_PRIORITY - 1);
        writerThread.start();
        Log.i(TAG, "Phone-side silent PCM AudioTrack started");
        return true;
    }

    synchronized boolean isPlaying() {
        AudioTrack current = audioTrack;
        if (!running || current == null) return false;
        try {
            return current.getState() == AudioTrack.STATE_INITIALIZED
                    && current.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
        } catch (RuntimeException error) {
            return false;
        }
    }

    void stop() {
        Thread oldWriter;
        AudioTrack oldTrack;
        synchronized (this) {
            running = false;
            generation++;
            oldWriter = writerThread;
            writerThread = null;
            oldTrack = audioTrack;
            audioTrack = null;
            if (oldTrack != null) {
                try { oldTrack.pause(); } catch (RuntimeException ignored) { }
                try { oldTrack.flush(); } catch (RuntimeException ignored) { }
                try { oldTrack.release(); } catch (RuntimeException ignored) { }
            }
        }
        if (oldWriter != null && oldWriter != Thread.currentThread()) {
            oldWriter.interrupt();
        }
        Log.i(TAG, "Phone-side silent PCM AudioTrack stopped");
    }

    private AudioTrack createTrack() {
        int minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);
        if (minimum <= 0) {
            Log.w(TAG, "AudioTrack minimum buffer unavailable: " + minimum);
            return null;
        }
        int bufferBytes = Math.max(minimum * 4, CHUNK_SAMPLES * 2 * 4);
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_MASK)
                    .setEncoding(ENCODING)
                    .build();
            AudioTrack created = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
            if (created.getState() != AudioTrack.STATE_INITIALIZED) {
                created.release();
                Log.w(TAG, "AudioTrack initialization failed");
                return null;
            }
            // PCM is all zeros as well; zero volume is a second guard against audible output.
            created.setVolume(0.0f);
            return created;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not create silent AudioTrack", error);
            return null;
        }
    }

    private void writeSilence(long writerGeneration) {
        short[] silence = new short[CHUNK_SAMPLES];
        int recoveries = 0;
        while (running && generation == writerGeneration) {
            AudioTrack current = audioTrack;
            if (current == null) break;
            int written;
            try {
                written = current.write(silence, 0, silence.length, AudioTrack.WRITE_BLOCKING);
            } catch (RuntimeException error) {
                Log.w(TAG, "Silent PCM write failed", error);
                written = AudioTrack.ERROR_DEAD_OBJECT;
            }
            if (written > 0) continue;
            if (written == 0) {
                Thread.yield();
                continue;
            }
            if (written == AudioTrack.ERROR_DEAD_OBJECT
                    && recoveries++ < MAX_DEAD_OBJECT_RECOVERIES) {
                if (!replaceDeadTrack(current, writerGeneration)) break;
                continue;
            }
            if (running) Log.w(TAG, "Silent PCM writer stopped; write result=" + written);
            break;
        }

        boolean failedUnexpectedly = false;
        synchronized (this) {
            if (running && generation == writerGeneration) {
                failedUnexpectedly = true;
                running = false;
                AudioTrack current = audioTrack;
                audioTrack = null;
                writerThread = null;
                if (current != null) {
                    try { current.release(); } catch (RuntimeException ignored) { }
                }
            }
        }
        if (failedUnexpectedly && failureListener != null) failureListener.run();
    }

    private synchronized boolean replaceDeadTrack(
            AudioTrack deadTrack, long writerGeneration) {
        if (!running || generation != writerGeneration || audioTrack != deadTrack) return false;
        audioTrack = null;
        try { deadTrack.release(); } catch (RuntimeException ignored) { }
        AudioTrack replacement = createTrack();
        if (replacement == null) return false;
        try {
            replacement.play();
            if (replacement.getState() != AudioTrack.STATE_INITIALIZED
                    || replacement.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                replacement.release();
                return false;
            }
            audioTrack = replacement;
            Log.i(TAG, "Recovered silent AudioTrack after DEAD_OBJECT");
            return true;
        } catch (RuntimeException error) {
            try { replacement.release(); } catch (RuntimeException ignored) { }
            Log.w(TAG, "Could not recover silent AudioTrack", error);
            return false;
        }
    }
}
