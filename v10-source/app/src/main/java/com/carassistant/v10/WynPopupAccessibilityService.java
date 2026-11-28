package com.carassistant.v10;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sends the phone to Home 30 seconds after the WYN launch command is accepted.
 * The timer deliberately does not depend on getRootInActiveWindow(): WYN's Flutter
 * SurfaceView and popup overlays do not always expose WYN as the active root.
 */
public final class WynPopupAccessibilityService extends AccessibilityService {
    private static final String WYN_PACKAGE = "com.wynauto.wyn";
    private static final String TAG = "CarAsistantWYN";
    private static final long POLL_MS = 100L;

    private static volatile WynPopupAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean checkScheduled;
    private boolean homeActionRequested;

    private final Runnable homeTimer = new Runnable() {
        @Override public void run() {
            checkScheduled = false;
            if (!isPending() || homeActionRequested) return;

            android.content.SharedPreferences prefs =
                    getSharedPreferences("wyn_auto_start", MODE_PRIVATE);
            long requestedAt = prefs.getLong("requested_at", 0L);
            long homeAt = prefs.getLong("home_at", requestedAt + 30000L);
            long now = System.currentTimeMillis();

            // Wait for the receiver to persist the accepted launch timestamp.
            if (requestedAt <= 0L || homeAt <= 0L) {
                scheduleCheck(POLL_MS);
                return;
            }

            long remaining = homeAt - now;
            if (remaining > 0L) {
                // One deadline timer, not 100-ms polling: keep the requested 30-second
                // interval measured from the accepted WYN launch timestamp.
                scheduleCheck(remaining);
                return;
            }

            homeActionRequested = true;
            boolean accepted = false;
            try {
                accepted = performGlobalAction(GLOBAL_ACTION_HOME);
            } catch (Throwable error) {
                Log.w(TAG, "Accessibility HOME action failed; trying root keyevent", error);
            }
            if (!accepted) {
                RootShellSession.EXEC.execute(() -> {
                    RootShellSession shell = new RootShellSession();
                    try {
                        shell.run(5, "input keyevent 3");
                        Log.i(TAG, "WYN_HOME root fallback accepted");
                    } catch (Throwable error) {
                        Log.e(TAG, "WYN_HOME root fallback failed", error);
                    } finally {
                        shell.destroy();
                    }
                });
            }
            Log.i(TAG, "WYN_30_SECOND_HOME; accessibilityAccepted=" + accepted
                    + "; elapsedSinceAcceptedLaunchMs=" + (now - requestedAt));
            clearPending();
        }
    };

    /** Immediate Home request used when media restoration finishes before WYN starts. */
    public static boolean requestPhoneHome() {
        WynPopupAccessibilityService service = instance;
        if (service == null) return false;
        try {
            boolean accepted = service.performGlobalAction(GLOBAL_ACTION_HOME);
            Log.i(TAG, "MEDIA_RECOVERY_HOME via WYN Accessibility accepted=" + accepted);
            return accepted;
        } catch (RuntimeException error) {
            Log.w(TAG, "MEDIA_RECOVERY_HOME via WYN Accessibility failed", error);
            return false;
        }
    }

    /** Called by WynAutoStartReceiver after am start has returned successfully. */
    public static void onWynLaunchAccepted() {
        Log.i(TAG, "WYN launch accepted; 30-second Home deadline armed from launch acceptance");
        WynPopupAccessibilityService service = instance;
        if (service != null) {
            service.main.post(() -> service.scheduleCheck(1L));
        }
    }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "WYN 30-second auto-Home service connected");
        if (isPending()) scheduleCheck(1L);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null
                || !WYN_PACKAGE.contentEquals(event.getPackageName())) return;
        if (isPending()) scheduleCheck(1L);
    }

    private void scheduleCheck(long delayMs) {
        if (checkScheduled || !isPending()) return;
        checkScheduled = true;
        main.postDelayed(homeTimer, Math.max(1L, delayMs));
    }

    private boolean isPending() {
        return getSharedPreferences("wyn_auto_start", MODE_PRIVATE)
                .getBoolean("background_after_load", false);
    }

    private void clearPending() {
        main.removeCallbacks(homeTimer);
        checkScheduled = false;
        getSharedPreferences("wyn_auto_start", MODE_PRIVATE).edit()
                .putBoolean("background_after_load", false)
                .remove("requested_at")
                .remove("home_at")
                .commit();
        homeActionRequested = false;
    }

    @Override public void onInterrupt() {
        // Accessibility event delivery may be interrupted while the service process
        // remains alive. Do not cancel the independent 30-second Home deadline.
        Log.w(TAG, "Accessibility event stream interrupted; keep WYN Home timer armed");
    }

    @Override public void onDestroy() {
        main.removeCallbacks(homeTimer);
        checkScheduled = false;
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
