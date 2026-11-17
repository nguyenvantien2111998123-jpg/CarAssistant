package com.carassistant.v10;

import android.content.Context;
import android.util.Log;

import com.carassistant.v10.core.car.CoreRecoveryMonitor;

/**
 * Process-scoped Core coordinator.
 *
 * Entry points are the boot receiver, the Gearhead host-state IPC callback,
 * and the Android Auto projection service. This is intentionally not a daemon:
 * Android may reclaim the process when no component is active.
 */
public final class CarAssistantCoreRuntime {
    private static final String TAG = "CarAsstCoreRuntime";
    private static volatile CarAssistantCoreRuntime instance;

    private final CoreRecoveryMonitor recoveryMonitor;
    private boolean started;

    private CarAssistantCoreRuntime(Context context) {
        Context app = context.getApplicationContext();
        recoveryMonitor = new CoreRecoveryMonitor(app != null ? app : context);
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
                    "Core coordinator initialized from " + entryPoint);
            started = true;
            Log.i(TAG, "Core coordinator ready; entryPoint=" + entryPoint);
        } catch (RuntimeException error) {
            recoveryMonitor.reportComponent("CORE_BOOTSTRAP", false,
                    "Core initialization failed: " + error.getClass().getSimpleName());
            Log.e(TAG, "Core initialization failed", error);
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
