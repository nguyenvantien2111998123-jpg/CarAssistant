package com.carassistant.v10;

import android.content.Context;
import android.util.Log;

import com.carassistant.v10.core.car.CoreRecoveryMonitor;

/**
 * Process-scoped Core runtime.
 *
 * Android Auto's bound CarActivityService is the authoritative lifecycle
 * signal. This runtime intentionally does not depend on Notification Access,
 * a phone Activity, or a permanently-running foreground service.
 */
public final class CarAssistantCoreRuntime {
    private static final String TAG = "CarAsstCoreRuntime";
    private static volatile CarAssistantCoreRuntime instance;

    private final CoreRecoveryMonitor recoveryMonitor;
    private boolean started;

    private CarAssistantCoreRuntime(Context context) {
        Context app = context.getApplicationContext();
        recoveryMonitor = new CoreRecoveryMonitor(app);
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
        try {
            recoveryMonitor.start(entryPoint);
            recoveryMonitor.reportComponent("CORE_BOOTSTRAP", true,
                    "Core bootstrapped from " + entryPoint);
            // The bound Android Auto projection service, not Notification Access,
            // reports connection/disconnection through its own lifecycle callbacks.
            recoveryMonitor.reportComponent("CORE_CONNECTION_MONITOR", true,
                    "Android Auto CarActivityService lifecycle is the connection authority");
            started = true;
            Log.i(TAG, "Core runtime ready; entryPoint=" + entryPoint);
        } catch (RuntimeException error) {
            recoveryMonitor.reportComponent("CORE_CONNECTION_MONITOR", false,
                    "Core lifecycle initialization failed: " + error.getClass().getSimpleName());
            recoveryMonitor.reportComponent("CORE_BOOTSTRAP", false,
                    "Core initialization failed: " + error.getClass().getSimpleName());
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
