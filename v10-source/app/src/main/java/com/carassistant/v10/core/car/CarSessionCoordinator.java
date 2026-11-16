package com.carassistant.v10.core.car;

/**
 * Coordinates three separate facts: Android Auto host connection, CarActivity
 * session lifecycle, and availability of the selected display backend.
 * A connection notification never starts an Activity or steals Android Auto
 * foreground. The backend attaches only after the host opens our car session.
 */
public final class CarSessionCoordinator {
    private final CarConnectionMonitor connectionMonitor;
    private final CarDisplayBackend displayBackend;
    private CarConnectionState connectionState = CarConnectionState.UNKNOWN;
    private boolean monitorStarted;
    private boolean sessionActive;
    private boolean displayAttached;

    public CarSessionCoordinator(CarConnectionMonitor monitor, CarDisplayBackend backend) {
        if (monitor == null) throw new IllegalArgumentException("connection monitor is required");
        if (backend == null) throw new IllegalArgumentException("display backend is required");
        connectionMonitor = monitor;
        displayBackend = backend;
    }

    public void start() {
        synchronized (this) {
            if (monitorStarted) return;
            monitorStarted = true;
        }
        try {
            connectionMonitor.start(this::onConnectionChanged);
        } catch (RuntimeException error) {
            synchronized (this) { monitorStarted = false; }
            throw error;
        }
    }

    public void stop() {
        boolean stopMonitor;
        synchronized (this) {
            stopMonitor = monitorStarted;
            monitorStarted = false;
            endSessionLocked();
        }
        if (stopMonitor) connectionMonitor.stop();
    }

    /** Called by the Android Auto adapter from the real CarActivity lifecycle. */
    public synchronized boolean onCarSessionStarted() {
        sessionActive = true;
        return attachBackendLocked();
    }

    public synchronized void onCarSessionStopped() { endSessionLocked(); }

    /** Connection state is informational; CONNECTED alone never attaches a display. */
    public synchronized void onConnectionChanged(CarConnectionState next) {
        if (next == null) return;
        connectionState = next;
        if (next == CarConnectionState.DISCONNECTED) endSessionLocked();
    }

    public synchronized boolean launchApp(String packageName, String activityName) {
        if (!sessionActive || !displayAttached || !isNonEmpty(packageName)
                || !isNonEmpty(activityName)) return false;
        return displayBackend.launchApp(packageName, activityName);
    }

    public synchronized boolean showHome() {
        return sessionActive && displayAttached && displayBackend.showHome();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(connectionState, monitorStarted, sessionActive,
                displayAttached, displayBackend.getId());
    }

    private boolean attachBackendLocked() {
        if (!sessionActive) return false;
        if (displayAttached) return true;
        if (!displayBackend.isAvailable()) return false;
        displayAttached = displayBackend.attach();
        return displayAttached;
    }

    private void endSessionLocked() {
        sessionActive = false;
        if (displayAttached) {
            displayAttached = false;
            displayBackend.detach();
        }
    }

    private static boolean isNonEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static final class Snapshot {
        public final CarConnectionState connectionState;
        public final boolean monitorStarted;
        public final boolean sessionActive;
        public final boolean displayAttached;
        public final String backendId;

        private Snapshot(CarConnectionState connectionState, boolean monitorStarted,
                         boolean sessionActive, boolean displayAttached, String backendId) {
            this.connectionState = connectionState;
            this.monitorStarted = monitorStarted;
            this.sessionActive = sessionActive;
            this.displayAttached = displayAttached;
            this.backendId = backendId;
        }
    }
}
