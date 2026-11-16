package com.carassistant.v10.core.car;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.util.UUID;

/**
 * Lightweight, process-level lifecycle and recovery diagnostics.
 *
 * It never starts feature services. It records process/reboot boundaries and
 * receives health reports from the projection service and Core components.
 */
public final class CoreRecoveryMonitor {
    private static final String TAG = "CarAsstCoreRecovery";
    private static final String PREFS = "carassistant_core_recovery";
    private static final long BOOT_MARKER_TOLERANCE_MS = 120_000L;

    private static final String KEY_PROCESS_ID = "process_instance_id";
    private static final String KEY_BOOT_MARKER = "boot_marker";
    private static final String KEY_RECOVERY_COUNT = "recovery_count";
    private static final String KEY_RECOVERY_REASON = "recovery_reason";
    private static final String KEY_CORE_STATE = "core_state";
    private static final String KEY_AA_STATE = "aa_state";
    private static final String KEY_SESSION_ID = "aa_session_id";

    private final SharedPreferences preferences;
    private final String processInstanceId = UUID.randomUUID().toString();
    private boolean started;

    public CoreRecoveryMonitor(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void start(String entryPoint) {
        if (started) return;
        started = true;

        long bootMarker = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        String previousProcess = preferences.getString(KEY_PROCESS_ID, "");
        long previousBootMarker = preferences.getLong(KEY_BOOT_MARKER, 0L);
        boolean hasPreviousProcess = previousProcess != null && !previousProcess.isEmpty();

        CoreRecoveryPolicy.RecoveryReason reason = CoreRecoveryPolicy.classify(
                hasPreviousProcess, previousBootMarker, bootMarker);
        int recoveryCount = preferences.getInt(KEY_RECOVERY_COUNT, 0);
        if (hasPreviousProcess && !processInstanceId.equals(previousProcess)) {
            recoveryCount++;
        }

        String previousCoreState = preferences.getString(KEY_CORE_STATE, "UNKNOWN");
        preferences.edit()
                .putString(KEY_PROCESS_ID, processInstanceId)
                .putLong(KEY_BOOT_MARKER, bootMarker)
                .putInt(KEY_RECOVERY_COUNT, recoveryCount)
                .putString(KEY_RECOVERY_REASON, reason.name())
                // Component health is process-local. Never trust READY flags from
                // the previous process when deciding whether this process is healthy.
                .putString("component_AA_PROJECTION_SERVICE", "NOT_READY")
                .putString("component_CORE_BOOTSTRAP", "NOT_READY")
                .putString("component_SESSION_COORDINATOR", "NOT_READY")
                .putString("component_CONNECTION_MONITOR", "NOT_READY")
                .putString("component_CORE_CONNECTION_MONITOR", "NOT_READY")
                .putString("component_DISPLAY_BACKEND", "NOT_READY")
                .putString(KEY_AA_STATE, "UNKNOWN")
                .putString(KEY_SESSION_ID, "")
                .putString(KEY_CORE_STATE, "PARTIAL")
                .putLong("last_bootstrap_at", System.currentTimeMillis())
                .apply();

        event("CORE_BOOTSTRAP_STARTED", "entryPoint=" + safe(entryPoint));
        event("CORE_RECOVERY_CLASSIFIED", "reason=" + reason.name()
                + ", recoveryCount=" + recoveryCount
                + ", previousCoreState=" + previousCoreState
                + ", processInstance=" + processInstanceId);
        event("CORE_MONITOR_READY", "stale process health flags cleared");
        updateCoreState();
    }

    public void reportComponent(String component, boolean ready, String detail) {
        if (component == null || !component.matches("[A-Za-z0-9_]{1,64}")) return;
        String state = ready ? "READY" : "NOT_READY";
        preferences.edit().putString("component_" + component, state).apply();
        event("CORE_COMPONENT", "name=" + component + ", state=" + state
                + ", detail=" + safe(detail));
        updateCoreState();
    }

    public void reportAndroidAutoConnection(boolean connected) {
        String next = connected ? "CONNECTED" : "DISCONNECTED";
        String previous = preferences.getString(KEY_AA_STATE, "UNKNOWN");
        preferences.edit().putString(KEY_AA_STATE, next).apply();

        if (connected && !"CONNECTED".equals(previous)) {
            String sessionId = UUID.randomUUID().toString();
            preferences.edit().putString(KEY_SESSION_ID, sessionId).apply();
            event("AA_SESSION_CREATED", "sessionId=" + sessionId);
        } else if (!connected && "CONNECTED".equals(previous)) {
            event("AA_SESSION_DISCONNECTED",
                    "sessionId=" + preferences.getString(KEY_SESSION_ID, "unknown"));
        }
        event("AA_CONNECTION_STATE", next);
    }

    public synchronized String getSnapshot() {
        return "coreState=" + preferences.getString(KEY_CORE_STATE, "UNKNOWN")
                + ", recoveryReason=" + preferences.getString(KEY_RECOVERY_REASON, "UNKNOWN")
                + ", recoveryCount=" + preferences.getInt(KEY_RECOVERY_COUNT, 0)
                + ", processInstance=" + processInstanceId
                + ", aaState=" + preferences.getString(KEY_AA_STATE, "UNKNOWN")
                + ", sessionId=" + preferences.getString(KEY_SESSION_ID, "none")
                + ", coreBootstrap=" + component("CORE_BOOTSTRAP")
                + ", projectionService=" + component("AA_PROJECTION_SERVICE")
                + ", coordinator=" + component("SESSION_COORDINATOR")
                + ", connectionMonitor=" + component("CONNECTION_MONITOR")
                + ", coreConnectionMonitor=" + component("CORE_CONNECTION_MONITOR")
                + ", displayBackend=" + component("DISPLAY_BACKEND");
    }

    private String component(String name) {
        return preferences.getString("component_" + name, "UNKNOWN");
    }

    private void updateCoreState() {
        boolean ready = CoreRecoveryPolicy.isCoreReady(
                "READY".equals(component("CORE_BOOTSTRAP")),
                "READY".equals(component("CORE_CONNECTION_MONITOR")));
        String next = ready ? "READY" : "PARTIAL";
        String previous = preferences.getString(KEY_CORE_STATE, "UNKNOWN");
        if (!next.equals(previous)) {
            preferences.edit().putString(KEY_CORE_STATE, next).apply();
            event("CORE_STATE_CHANGED", previous + " -> " + next);
        }
    }

    private void event(String name, String detail) {
        String message = name + " | " + detail;
        preferences.edit()
                .putString("last_event", name)
                .putString("last_event_detail", detail)
                .putLong("last_event_at", System.currentTimeMillis())
                .apply();
        Log.i(TAG, message);
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
