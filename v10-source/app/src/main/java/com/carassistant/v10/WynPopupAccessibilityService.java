package com.carassistant.v10;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Owns only the fixed 30-second WYN background deadline.
 * No Flutter readiness polling, UI-tree inspection or repeated event-driven checks.
 */
public final class WynPopupAccessibilityService extends AccessibilityService {
    private static final String TAG = "CarAsistantWYN";
    private static final long HOME_DELAY_MS = 30000L;

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
            long acceptedAt = prefs.getLong("launch_accepted_elapsed", 0L);
            long homeDeadline = prefs.getLong("home_deadline_elapsed", 0L);
            long now = SystemClock.elapsedRealtime();

            // The receiver writes both timestamps and the pending flag in one commit.
            // Missing/invalid timestamps indicate stale state, not a reason to poll.
            if (acceptedAt <= 0L || homeDeadline <= 0L || now < acceptedAt) {
                Log.e(TAG, "Invalid WYN deadline state; clearing stale timer");
                clearPending();
                return;
            }

            long remaining = homeDeadline - now;
            if (remaining > 0L) {
                scheduleCheck(remaining);
                return;
            }

            homeActionRequested = true;
            boolean accepted = false;
            try {
                accepted = performGlobalAction(GLOBAL_ACTION_HOME);
            } catch (Throwable error) {
                Log.w(TAG, "Accessibility HOME action failed; trying dedicated root thread", error);
            }

            if (accepted) {
                Log.i(TAG, "WYN_HOME_30S accessibilityAccepted=true; elapsedSinceAcceptedLaunchMs="
                        + (now - acceptedAt));
                clearPending();
                return;
            }

            // Do not queue the fallback behind the shared single-thread shell executor.
            new Thread(() -> {
                RootShellSession shell = new RootShellSession();
                try {
                    shell.run(5, "input keyevent 3");
                    Log.i(TAG, "WYN_HOME_30S rootFallbackAccepted; elapsedSinceAcceptedLaunchMs="
                            + (SystemClock.elapsedRealtime() - acceptedAt));
                } catch (Throwable error) {
                    Log.e(TAG, "WYN_HOME_30S root fallback failed", error);
                } finally {
                    shell.destroy();
                    main.post(this::clearPending);
                }
            }, "CarAsistant-WYN-Home").start();
        }
    };

    /** Called after am start has returned successfully and the 30-second deadline is saved. */
    public static void onWynLaunchAccepted() {
        WynPopupAccessibilityService service = instance;
        if (service != null) {
            service.main.post(() -> service.scheduleCheck(1L));
        } else {
            Log.i(TAG, "WYN launch accepted; deadline persisted for Accessibility service reconnect");
        }
    }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "WYN fixed 30-second Home timer service connected");
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
                .remove("launch_accepted_elapsed")
                .remove("home_deadline_elapsed")
                .commit();
        homeActionRequested = false;
    }

    @Override public void onInterrupt() {
        // Keep the independently scheduled deadline intact.
    }

    @Override public void onDestroy() {
        main.removeCallbacks(homeTimer);
        checkScheduled = false;
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
