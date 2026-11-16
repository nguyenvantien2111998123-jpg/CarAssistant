package com.carassistant.v10.core.car;

/**
 * Coordinates host connection signals, the real CarActivity lifecycle and a
 * pluggable display backend. Connection notifications are informational only:
 * they never create/end a car session or attach/detach a display.
 */
public final class CarSessionCoordinator {
    private final Object lock = new Object();
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
        synchronized (lock) {
            if (monitorStarted) return;
            monitorStarted = true;
        }
        try {
            connectionMonitor.start(this::onConnectionChanged);
        } catch (RuntimeException error) {
            synchronized (lock) { monitorStarted = false; }
            throw error;
        }
    }

    public void stop() {
        boolean stopMonitor;
        boolean detach;
        synchronized (lock) {
            stopMonitor = monitorStarted;
            monitorStarted = false;
            sessionActive = false;
            detach = displayAttached;
            displayAttached = false;
        }
        if (stopMonitor) connectionMonitor.stop();
        if (detach) displayBackend.detach();
    }

    /** Called only from the host-owned CarActivity lifecycle after UI surfaces exist. */
    public boolean onCarSessionStarted() {
        synchronized (lock) {
            sessionActive = true;
            if (displayAttached) return true;
        }
        if (!displayBackend.isAvailable()) return false;
        boolean attached = displayBackend.attach();
        boolean keep;
        synchronized (lock) {
            keep = attached && sessionActive;
            displayAttached = keep;
        }
        if (attached && !keep) displayBackend.detach();
        return keep;
    }

    public void onCarSessionStopped() {
        boolean detach;
        synchronized (lock) {
            sessionActive = false;
            detach = displayAttached;
            displayAttached = false;
        }
        if (detach) displayBackend.detach();
    }

    /** Host connection state is diagnostic; only CarActivity lifecycle owns session lifetime. */
    public void onConnectionChanged(CarConnectionState next) {
        if (next == null) return;
        synchronized (lock) { connectionState = next; }
    }

    public boolean launchApp(String packageName, String activityName) {
        synchronized (lock) {
            if (!sessionActive || !displayAttached || !isNonEmpty(packageName)
                    || !isNonEmpty(activityName)) return false;
        }
        return displayBackend.launchApp(packageName, activityName);
    }

    public boolean showHome() {
        synchronized (lock) {
            if (!sessionActive || !displayAttached) return false;
        }
        return displayBackend.showHome();
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(connectionState, monitorStarted, sessionActive,
                    displayAttached, displayBackend.getId());
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