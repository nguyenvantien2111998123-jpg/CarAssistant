package com.carassistant.v10;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Event-driven WYN startup completion. Once WYN has emitted several real UI
 * content events and its window remains foreground for a short settle period,
 * send exactly one HOME action. No periodic root-window polling.
 */
public final class WynPopupAccessibilityService extends AccessibilityService {
    private static final String WYN_PACKAGE = "com.wynauto.wyn";
    private static final String TAG = "CarAsistantWYN";
    private static final long HOME_STABLE_MS = 4000L;
    private static final long HOME_MAX_WAIT_MS = 25000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean homeCheckScheduled;
    private boolean homeActionRequested;
    private long wynFirstSeenAt;
    private long lastEventLog;
    private int contentEvents;

    private final Runnable backgroundCheck = () -> {
        homeCheckScheduled = false;
        if (!isPending() || homeActionRequested) return;

        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) {
                Log.d(TAG, "WYN active-window root not ready; waiting for the next real event");
                return;
            }
            boolean foreground = root.getPackageName() != null
                    && WYN_PACKAGE.contentEquals(root.getPackageName());
            long elapsed = wynFirstSeenAt == 0L ? 0L
                    : System.currentTimeMillis() - wynFirstSeenAt;

            if (!foreground) {
                // Another app/system surface took foreground. Do not fight it.
                Log.i(TAG, "WYN no longer foreground; cancel auto-Home without relaunching");
                clearPending();
                return;
            }

            if (contentEvents >= 4 && elapsed >= HOME_STABLE_MS) {
                homeActionRequested = true;
                boolean accepted = performGlobalAction(GLOBAL_ACTION_HOME);
                Log.i(TAG, "WYN UI stable; single HOME action accepted=" + accepted
                        + "; elapsedMs=" + elapsed + "; contentEvents=" + contentEvents);
                clearPending();
                return;
            }

            if (elapsed >= HOME_MAX_WAIT_MS) {
                Log.w(TAG, "WYN Home readiness not confirmed within limit; leave WYN foreground");
                clearPending();
            }
            // No timer loop: wait for the next real WYN accessibility event.
        } catch (Throwable error) {
            Log.w(TAG, "WYN auto-Home event check failed", error);
            // Avoid repeated global actions. A later genuine event can schedule one retry.
        } finally {
            if (root != null) root.recycle();
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "WYN Accessibility connected; popup frames are captured only from WYN overlay");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null
                || !WYN_PACKAGE.contentEquals(event.getPackageName())) return;

        long now = System.currentTimeMillis();
        if (wynFirstSeenAt == 0L && isPending()) wynFirstSeenAt = now;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            contentEvents++;
        }

        if (now - lastEventLog >= 2000L) {
            lastEventLog = now;
            Log.i(TAG, "DIAG_ACCESSIBILITY_EVENT type="
                    + AccessibilityEvent.eventTypeToString(type)
                    + " windowId=" + event.getWindowId()
                    + " class=" + (event.getClassName() == null ? "" : event.getClassName())
                    + " contentEvents=" + contentEvents
                    + " autoBackgroundPending=" + isPending());
        }

        if (!isPending() || homeActionRequested) return;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            scheduleCheck();
        }
    }

    private void scheduleCheck() {
        if (homeCheckScheduled || !isPending()) return;
        homeCheckScheduled = true;
        main.postDelayed(backgroundCheck, HOME_STABLE_MS);
    }

    private boolean isPending() {
        return getSharedPreferences("wyn_auto_start", MODE_PRIVATE)
                .getBoolean("background_after_load", false);
    }

    private void clearPending() {
        main.removeCallbacks(backgroundCheck);
        homeCheckScheduled = false;
        getSharedPreferences("wyn_auto_start", MODE_PRIVATE).edit()
                .putBoolean("background_after_load", false)
                .remove("requested_at")
                .commit();
        wynFirstSeenAt = 0L;
        contentEvents = 0;
        homeActionRequested = false;
    }

    @Override public void onInterrupt() {
        main.removeCallbacks(backgroundCheck);
        homeCheckScheduled = false;
    }
}
