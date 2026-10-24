package com.carassistant.v10;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;
import java.util.Random;

/** Quiet white-noise bed; intentionally does not request audio focus. */
final class V10WhiteNoisePlayer {
    private static final String TAG = "V10WhiteNoise";
    private static final int RATE = 22050;
    private static final int SAMPLES = 2048;
    private static final float VOLUME = 0.012f;
    private volatile boolean running;
    private Thread worker;
    private AudioTrack track;

    synchronized void start() {
        if (running) return;
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, SAMPLES * 2), AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release(); track = null;
                Log.w(TAG, "AudioTrack initialization failed");
                return;
            }
            track.setVolume(VOLUME);
            track.play();
            running = true;
            worker = new Thread(this::writeNoise, "CarAsistant-WhiteNoise");
            worker.setDaemon(true);
            worker.start();
            Log.i(TAG, "Quiet white-noise bed started without audio focus");
        } catch (Throwable e) {
            Log.w(TAG, "Unable to start white noise", e);
            running = false;
            if (track != null) { try { track.release(); } catch (Throwable ignored) {} track = null; }
        }
    }

    private void writeNoise() {
        short[] buffer = new short[SAMPLES];
        Random random = new Random();
        while (running) {
            for (int i = 0; i < buffer.length; i++) buffer[i] = (short) random.nextInt(32768);
            AudioTrack current = track;
            if (current == null) return;
            try {
                if (current.write(buffer, 0, buffer.length) < 0) return;
            } catch (Throwable e) {
                if (running) Log.w(TAG, "Noise writer stopped", e);
                return;
            }
        }
    }

    synchronized void stop() {
        running = false;
        if (worker != null) { worker.interrupt(); worker = null; }
        if (track != null) {
            try { track.pause(); } catch (Throwable ignored) {}
            try { track.flush(); } catch (Throwable ignored) {}
            try { track.stop(); } catch (Throwable ignored) {}
            try { track.release(); } catch (Throwable ignored) {}
            track = null;
        }
    }
}
