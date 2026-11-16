package com.carassistant.v10;

import android.content.Context;
import android.util.Log;

import com.carassistant.v10.core.car.CoreRecoveryMonitor;
import com.carassistant.v10.core.car.SharedPreferencesCarConnectionMonitor;

/**
 * Process-scoped Core runtime. It is bootstrapped by Android Auto's bound
 * projection service, not by a phone Activity. It deliberately does not own
 * microphone or media foreground services.
 */
public final class CarAssistantCoreRuntime {
    private static final String TAG = "CarAsstCoreRuntime";
    private static volatile CarAssistantCoreRuntime instance;

    private final CoreRecoveryMonitor recoveryMonitor;
    private final SharedPreferencesCarConnectionMonitor connectionMonitor;
    private boolean started;

    private CarAssistantCoreRuntime(Context context) {
        Context app = context.getApplicationContext();
        recoveryMonitor = new CoreRecoveryMonitor(app);
        connectionMonitor = new SharedPreferencesCarConnectionMonitor(app);
    }

    public static CarAssistantCoreRuntime start(Context context, String entryPoint) {
        if (context == null) throw new IllegalArgumentException("context is required");
        CarAssistantCoreRuntime current = instance;
        if (current == null) {
            synchronized (CarAssistantCoreRuntime.class) {
                current = instance;
                if (current == null) {
                    current = new CarAssistantCoreRuntime(context);
                    instance = current;
                }
            }
        }
        current.startOnce(entryPoint);
        return current;
    }

    private synchronized void startOnce(String entryPoint) {
        if (started) {
            Log.d(TAG, "Ignoring duplicate Core start from " + entryPoint);
            return;
        }
        started = true;
        recoveryMonitor.start(entryPoint);
        recoveryMonitor.reportComponent("CORE_BOOTSTRAP", true,
                "Android Auto bound the projection service");
        try {
            connectionMonitor.start(connected ->
                    recoveryMonitor.reportAndroidAutoConnection(
                            connected == com.carassistant.v10.core.car.CarConnectionState.CONNECTED));
            recoveryMonitor.reportComponent("CORE_CONNECTION_MONITOR", true,
                    "Core-owned monitor observes the existing Android Auto connection signal");
            Log.i(TAG, "Core runtime ready; entryPoint=" + entryPoint);
        } catch (RuntimeException error) {
            recoveryMonitor.reportComponent("CORE_CONNECTION_MONITOR", false,
                    "Connection monitor failed: " + error.getClass().getSimpleName());
            Log.e(TAG, "Core runtime initialization failed", error);
        }
    }

    public static void reportComponent(String component, boolean ready, String detail) {
        CarAssistantCoreRuntime current = instance;
        if (current != null) current.recoveryMonitor.reportComponent(component, ready, detail);
    }

    public static void reportAndroidAutoConnection(boolean connected) {
        CarAssistantCoreRuntime current = instance;
        if (current != null) current.recoveryMonitor.reportAndroidAutoConnection(connected);
    }

    public static String getDiagnostics() {
        CarAssistantCoreRuntime current = instance;
        return current == null ? "CORE_NOT_STARTED" : current.recoveryMonitor.getSnapshot();
    }
}
