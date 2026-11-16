package com.carassistant.v10.core.car;

/**
 * Platform adapter that reports Android Auto connection changes.
 * Core deliberately has no dependency on NotificationListener, LSPosed,
 * Shizuku, or Android Auto implementation details.
 */
public interface CarConnectionMonitor {
    interface Listener {
        void onConnectionChanged(CarConnectionState state);
    }

    void start(Listener listener);
    void stop();
}
