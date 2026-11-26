package com.carassistant.v10;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** Waits for WYN Home to stabilize, then backgrounds its task without closing it. */
public final class WynPopupAccessibilityService extends AccessibilityService {
    private static final String WYN_PACKAGE = "com.wynauto.wyn";
    private static final String TAG = "CarAsistantWYN";
    private static final long HOME_WAIT_MS = 12000L;
    private static final long HOME_MAX_WAIT_MS = 45000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean homeCheckScheduled;
    private long wynFirstSeenAt;
    private long lastEventLog;
    private int contentEvents;

    private final Runnable backgroundCheck = new Runnable() {
        @Override public void run() {
            if (!isAutoBackgroundPending()) { homeCheckScheduled = false; return; }
            AccessibilityNodeInfo root = null;
            try {
                root = getRootInActiveWindow();
                boolean foreground = root != null && root.getPackageName() != null
                        && WYN_PACKAGE.contentEquals(root.getPackageName());
                long elapsed = System.currentTimeMillis() - wynFirstSeenAt;
                if (foreground && contentEvents >= 2 && elapsed >= HOME_WAIT_MS) {
                    boolean accepted = performGlobalAction(GLOBAL_ACTION_HOME);
                    Log.i(TAG, "WYN Home stabilized; background request accepted=" + accepted
                            + "; elapsedMs=" + elapsed + "; contentEvents=" + contentEvents);
                    if (accepted) { clearPending(); homeCheckScheduled = false; return; }
                }
                if (!foreground) {
                    Log.i(TAG, "WYN left foreground before stabilization; preserving current foreground app");
                    clearPending(); homeCheckScheduled = false; return;
                }
                if (elapsed >= HOME_MAX_WAIT_MS) {
                    Log.w(TAG, "WYN did not stabilize in time; leaving WYN visible instead of closing its task");
                    clearPending(); homeCheckScheduled = false; return;
                }
                main.postDelayed(this, 1500L);
            } catch (Throwable error) {
                Log.w(TAG, "WYN auto-background check failed", error);
                main.postDelayed(this, 1500L);
            } finally { if (root != null) root.recycle(); }
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "WYN Accessibility connected; popup pixels use WYN's own window surface");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null
                || !WYN_PACKAGE.contentEquals(event.getPackageName())) return;
        long now = System.currentTimeMillis();
        if (now - lastEventLog >= 1000L) {
            lastEventLog = now;
            Log.i(TAG, "DIAG_ACCESSIBILITY_EVENT type="
                    + AccessibilityEvent.eventTypeToString(event.getEventType())
                    + " windowId=" + event.getWindowId()
                    + " class=" + (event.getClassName() == null ? "" : event.getClassName())
                    + " contentEvents=" + contentEvents
                    + " autoBackgroundPending=" + isPending());
        }
        if (!isPending()) return;
        if (wynFirstSeenAt == 0L) wynFirstSeenAt = now;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) contentEvents++;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) scheduleCheck();
    }

    private void scheduleCheck() {
        if (homeCheckScheduled) return;
        homeCheckScheduled = true;
        main.postDelayed(backgroundCheck, HOME_WAIT_MS);
    }
    private boolean isPending() {
        return getSharedPreferences("wyn_auto_start", MODE_PRIVATE)
                .getBoolean("background_after_load", false);
    }
    private void clearPending() {
        getSharedPreferences("wyn_auto_start", MODE_PRIVATE).edit()
                .putBoolean("background_after_load", false).remove("requested_at").commit();
        wynFirstSeenAt = 0L;
        contentEvents = 0;
    }
    @Override public void onInterrupt() {
        main.removeCallbacks(backgroundCheck);
        homeCheckScheduled = false;
    }
}