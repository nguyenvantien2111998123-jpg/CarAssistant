package com.carassistant.v10.core.car;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Reads the existing MediaSessionBridge connection flag without depending on
 * NotificationListenerService or Android Auto internals. This is a hint only;
 * CarActivity lifecycle remains the authority for an active car session.
 */
public final class SharedPreferencesCarConnectionMonitor
        implements CarConnectionMonitor, SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String PREFS = "carassistant_media_resume";
    private static final String KEY_CONNECTED = "aa_connected";
    private final SharedPreferences preferences;
    private Listener listener;
    private boolean started;

    public SharedPreferencesCarConnectionMonitor(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override public synchronized void start(Listener value) {
        if (started) return;
        listener = value;
        started = true;
        preferences.registerOnSharedPreferenceChangeListener(this);
        emitCurrentState();
    }

    @Override public synchronized void stop() {
        if (!started) return;
        started = false;
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        listener = null;
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (!KEY_CONNECTED.equals(key)) return;
        emitCurrentState();
    }

    private void emitCurrentState() {
        Listener current;
        synchronized (this) {
            if (!started) return;
            current = listener;
        }
        if (current != null) {
            current.onConnectionChanged(preferences.getBoolean(KEY_CONNECTED, false)
                    ? CarConnectionState.CONNECTED : CarConnectionState.DISCONNECTED);
        }
    }
}