package com.carassistant.v10;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Observes available audio outputs before automatic media resume.
 *
 * Android exposes connected output devices here, but does not expose the active
 * output route of another app's player. We therefore require a non-built-in output
 * to remain available and stable before issuing Play; we never change routing or
 * request audio focus on behalf of the source player.
 */
final class MediaAudioRouteGate {
    interface Listener {
        void onReadinessChanged(boolean stable, String outputs);
    }

    private static final String TAG = "CarAssistantAudioRoute";
    private static final long STABLE_WINDOW_MS = 2500L;
    private static final long POLL_INTERVAL_MS = 500L;

    private final AudioManager audioManager;
    private final Handler handler;
    private final Listener listener;
    private final Runnable poll = this::refresh;
    private final AudioDeviceCallback callback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            refresh();
        }

        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            refresh();
        }
    };

    private String lastSignature = "";
    private long stableSince;
    private boolean stable;
    private boolean started;

    MediaAudioRouteGate(Context context, Handler handler, Listener listener) {
        this.audioManager = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        this.handler = handler;
        this.listener = listener;
    }

    void start() {
        if (started) return;
        started = true;
        if (audioManager == null) {
            Log.w(TAG, "AudioManager unavailable; automatic autoplay remains gated");
            return;
        }
        try {
            audioManager.registerAudioDeviceCallback(callback, handler);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not register audio output observer", error);
        }
        refresh();
    }

    boolean isExternalOutputStable() {
        if (!started || audioManager == null) return false;
        refresh();
        return stable;
    }

    private void refresh() {
        if (!started || audioManager == null) return;
        AudioDeviceInfo[] devices;
        try {
            devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not enumerate audio outputs", error);
            devices = new AudioDeviceInfo[0];
        }

        List<String> descriptions = new ArrayList<>();
        boolean externalOutput = false;
        if (devices != null) {
            for (AudioDeviceInfo device : devices) {
                if (device == null) continue;
                int type = device.getType();
                String name = String.valueOf(device.getProductName());
                String address;
                try {
                    address = device.getAddress();
                } catch (RuntimeException ignored) {
                    address = "";
                }
                descriptions.add(type + ":" + device.getId() + ":" + name + ":" + address);
                if (!isBuiltInOutput(type)) externalOutput = true;
            }
        }
        Collections.sort(descriptions);
        String signature = descriptions.toString();
        long now = SystemClock.elapsedRealtime();
        if (!signature.equals(lastSignature)) {
            lastSignature = signature;
            stableSince = externalOutput ? now : 0L;
        } else if (!externalOutput) {
            stableSince = 0L;
        }

        boolean nextStable = externalOutput && stableSince > 0L
                && now - stableSince >= STABLE_WINDOW_MS;
        if (nextStable != stable) {
            stable = nextStable;
            String outputText = descriptions.toString();
            Log.i(TAG, "External output readiness changed to " + stable + ": " + outputText);
            if (listener != null) listener.onReadinessChanged(stable, outputText);
        }

        handler.removeCallbacks(poll);
        handler.postDelayed(poll, POLL_INTERVAL_MS);
    }

    private static boolean isBuiltInOutput(int type) {
        return type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                || type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                || type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE;
    }

    void stop() {
        if (!started) return;
        started = false;
        handler.removeCallbacks(poll);
        if (audioManager != null) {
            try {
                audioManager.unregisterAudioDeviceCallback(callback);
            } catch (RuntimeException error) {
                Log.d(TAG, "Audio output observer already unregistered", error);
            }
        }
        stable = false;
        stableSince = 0L;
    }
}
