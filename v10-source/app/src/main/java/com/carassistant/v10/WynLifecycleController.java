package com.carassistant.v10;

import android.content.Context;
import android.util.Log;

/**
 * Fail-closed WYN lifecycle gate.
 *
 * The current Android 12/LSPosed implementation has only validated the
 * visible-launch-then-background flow. It has NOT validated that WYN's
 * Activity can be launched on an app-owned VirtualDisplay, nor that a
 * standalone FlutterEngine can recreate WYN's plugins, Rust bridge and
 * location subscriptions.
 *
 * Until one of those runtime strategies is proven on the target device, do
 * not launch WYN. In particular, never fall back to the phone's default
 * display and never use a delayed HOME action as a substitute for headless
 * execution.
 *
 * This intentionally prioritizes the no-physical-UI requirement over
 * starting an unverified WYN runtime. It is not a claim that WYN is currently
 * running headlessly.
 */
public final class WynLifecycleController {
    private static final String TAG = "CarAsstWynLifecycle";

    public enum State {
        STOPPED,
        START_REQUESTED,
        RUNTIME_UNVERIFIED_BLOCKED,
        BACKGROUND_ACTIVE,
        ERROR
    }

    private static final Object LOCK = new Object();
    private static volatile State state = State.STOPPED;

    private WynLifecycleController() { }

    /**
     * Called when Android Auto connects.
     *
     * Deliberately does not call startActivity(), am start, createVirtualDisplay()
     * or moveTaskToBack(). The app-owned VirtualDisplay route is not proven to
     * be authorized to host WYN's Activity on Android 12. Creating a display
     * object alone is not sufficient evidence.
     */
    public static void onAndroidAutoConnected(Context context) {
        if (context == null) {
            state = State.ERROR;
            Log.e(TAG, "AA connected callback received null Context");
            return;
        }

        synchronized (LOCK) {
            if (state == State.BACKGROUND_ACTIVE) {
                Log.i(TAG, "WYN runtime already reported active");
                return;
            }
            state = State.START_REQUESTED;
            Log.i(TAG, "State -> START_REQUESTED");

            // Fail closed: no verified non-physical WYN runtime is currently
            // available in this build. Do not expose WYN on Display 0.
            state = State.RUNTIME_UNVERIFIED_BLOCKED;
            Log.e(TAG, "State -> RUNTIME_UNVERIFIED_BLOCKED: refusing WYN launch; "
                    + "no verified non-physical Activity/Flutter runtime is installed");
        }
    }

    /**
     * The disconnect signal only resets the launch gate. It does not force-stop
     * WYN, remove its task, or tear down a runtime owned by another process.
     */
    public static void onAndroidAutoDisconnected() {
        synchronized (LOCK) {
            if (state != State.BACKGROUND_ACTIVE) {
                state = State.STOPPED;
            }
            Log.i(TAG, "Android Auto disconnected; lifecycle gate state=" + state);
        }
    }

    public static State getCurrentState() {
        return state;
    }

    public static boolean isBackgroundActive() {
        return state == State.BACKGROUND_ACTIVE;
    }
}
