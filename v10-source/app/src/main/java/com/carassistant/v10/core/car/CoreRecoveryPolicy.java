package com.carassistant.v10.core.car;

/** Pure policy helpers so recovery classification and readiness can be unit tested. */
public final class CoreRecoveryPolicy {
    private static final long REBOOT_MARKER_THRESHOLD_MS = 120_000L;

    public enum RecoveryReason {
        FIRST_START,
        PROCESS_RESTART,
        DEVICE_REBOOT
    }

    private CoreRecoveryPolicy() { }

    public static RecoveryReason classify(boolean hasPreviousProcess,
                                           long previousBootMarker,
                                           long currentBootMarker) {
        if (!hasPreviousProcess) return RecoveryReason.FIRST_START;
        if (previousBootMarker > 0L && currentBootMarker > 0L
                && Math.abs(currentBootMarker - previousBootMarker)
                > REBOOT_MARKER_THRESHOLD_MS) {
            return RecoveryReason.DEVICE_REBOOT;
        }
        return RecoveryReason.PROCESS_RESTART;
    }

    /** Whether a persisted AA host session must be invalidated at bootstrap. */
    public static boolean shouldResetHostSession(RecoveryReason reason) {
        return reason == RecoveryReason.DEVICE_REBOOT
                || reason == RecoveryReason.FIRST_START;
    }

    /**
     * Core coordinator readiness is independent from the optional AA projection
     * session. A boot receiver can initialize Core before a car is connected.
     */
    public static boolean isCoreReady(boolean coreBootstrapReady) {
        return coreBootstrapReady;
    }

    /** Compatibility overload retained for older callers/tests. */
    public static boolean isCoreReady(boolean projectionServiceReady,
                                      boolean coreConnectionMonitorReady) {
        return projectionServiceReady && coreConnectionMonitorReady;
    }

    /** Legacy three-component overload retained for older callers/tests. */
    public static boolean isCoreReady(boolean projectionServiceReady,
                                      boolean coordinatorReady,
                                      boolean connectionMonitorReady) {
        return projectionServiceReady && coordinatorReady && connectionMonitorReady;
    }
}
