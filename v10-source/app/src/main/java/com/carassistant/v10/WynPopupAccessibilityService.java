package com.carassistant.v10;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** Returns to phone Home exactly 30 seconds after WYN is launched on display 0. */
public final class WynPopupAccessibilityService extends AccessibilityService {
    private static final String WYN_PACKAGE = "com.wynauto.wyn";
    private static final String TAG = "CarAsistantWYN";
    private static final long POLL_MS = 250L;
    private static final long MAX_LAUNCH_WAIT_MS = 60000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean checkScheduled;
    private boolean homeActionRequested;
    private long wynFirstSeenAt;

    private final Runnable homeTimer = new Runnable() {
        @Override public void run() {
            checkScheduled = false;
            if (!isPending() || homeActionRequested) return;
            long now = System.currentTimeMillis();
            android.content.SharedPreferences prefs =
                    getSharedPreferences("wyn_auto_start", MODE_PRIVATE);
            long requestedAt = prefs.getLong("requested_at", now);
            long homeAt = prefs.getLong("home_at", requestedAt + 30000L);
            AccessibilityNodeInfo root = null;
            try {
                root = getRootInActiveWindow();
                if (root == null) {
                    if (now - requestedAt > MAX_LAUNCH_WAIT_MS) {
                        Log.w(TAG, "WYN did not become the active phone window; leave current screen unchanged");
                        clearPending();
                    } else scheduleCheck(POLL_MS);
                    return;
                }
                CharSequence packageName = root.getPackageName();
                boolean wynForeground = packageName != null && WYN_PACKAGE.contentEquals(packageName);
                if (!wynForeground) {
                    if (wynFirstSeenAt > 0L) {
                        Log.i(TAG, "WYN left foreground before 30-second timer; cancel automatic Home");
                        clearPending();
                    } else if (now - requestedAt > MAX_LAUNCH_WAIT_MS) {
                        Log.w(TAG, "WYN launch was not observed within 60 seconds; cancel timer");
                        clearPending();
                    } else scheduleCheck(POLL_MS);
                    return;
                }
                if (wynFirstSeenAt == 0L) wynFirstSeenAt = now;
                if (now >= homeAt) {
                    homeActionRequested = true;
                    boolean accepted = performGlobalAction(GLOBAL_ACTION_HOME);
                    Log.i(TAG, "WYN_30_SECOND_HOME; accepted=" + accepted
                            + "; elapsedSinceLaunchMs=" + (now - requestedAt));
                    clearPending();
                    return;
                }
                scheduleCheck(Math.min(POLL_MS, Math.max(50L, homeAt - now)));
            } catch (Throwable error) {
                Log.w(TAG, "Could not inspect WYN foreground before scheduled Home", error);
                scheduleCheck(POLL_MS);
            } finally {
                if (root != null) root.recycle();
            }
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "WYN 30-second auto-Home service connected");
        if (isPending()) scheduleCheck(100L);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null
                || !WYN_PACKAGE.contentEquals(event.getPackageName())) return;
        if (wynFirstSeenAt == 0L) wynFirstSeenAt = System.currentTimeMillis();
        if (isPending()) scheduleCheck(50L);
    }

    private void scheduleCheck(long delayMs) {
        if (checkScheduled || !isPending()) return;
        checkScheduled = true;
        main.postDelayed(homeTimer, delayMs);
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
        wynFirstSeenAt = 0L;
        homeActionRequested = false;
    }

    @Override public void onInterrupt() {
        main.removeCallbacks(homeTimer);
        checkScheduled = false;
    }
}
