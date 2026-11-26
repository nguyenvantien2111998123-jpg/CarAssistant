package com.carassistant.v10;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Waits for WYN's actual Home UI tree to finish settling before returning the
 * phone to its launcher. A short Activity-resume or first-frame event is not
 * considered proof that WYN has loaded its traffic data.
 */
public final class WynPopupAccessibilityService extends AccessibilityService {
    private static final String WYN_PACKAGE = "com.wynauto.wyn";
    private static final String TAG = "CarAsistantWYN";
    private static final long MIN_READY_MS = 12000L;
    private static final long READY_STABLE_MS = 2500L;
    private static final long CHECK_INTERVAL_MS = 1500L;
    private static final long MAX_WAIT_MS = 60000L;
    private static final int MIN_CONTENT_EVENTS = 8;
    private static final int MIN_ACCESSIBILITY_NODES = 1;
    private static final int MAX_TREE_NODES = 300;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean checkScheduled;
    private boolean homeActionRequested;
    private long wynFirstSeenAt;
    private long lastEventLog;
    private long stableSince;
    private int contentEvents;
    private String stableFingerprint;

    private final Runnable readinessCheck = new Runnable() {
        @Override public void run() {
            checkScheduled = false;
            if (!isPending() || homeActionRequested) return;

            AccessibilityNodeInfo root = null;
            try {
                root = getRootInActiveWindow();
                if (root == null) {
                    scheduleCheck(CHECK_INTERVAL_MS);
                    return;
                }
                CharSequence rootPackage = root.getPackageName();
                if (rootPackage == null || !WYN_PACKAGE.contentEquals(rootPackage)) {
                    Log.i(TAG, "WYN left foreground before readiness; cancel auto-Home");
                    clearPending();
                    return;
                }

                long now = System.currentTimeMillis();
                long elapsed = wynFirstSeenAt == 0L ? 0L : now - wynFirstSeenAt;
                TreeState tree = inspectTree(root);
                boolean readyCandidate = contentEvents >= MIN_CONTENT_EVENTS
                        && elapsed >= MIN_READY_MS
                        && tree.nodeCount >= MIN_ACCESSIBILITY_NODES
                        && !tree.loading;

                if (readyCandidate) {
                    if (tree.fingerprint.equals(stableFingerprint)) {
                        if (stableSince == 0L) stableSince = now;
                    } else {
                        stableFingerprint = tree.fingerprint;
                        stableSince = now;
                    }
                    if (now - stableSince >= READY_STABLE_MS) {
                        homeActionRequested = true;
                        boolean accepted = performGlobalAction(GLOBAL_ACTION_HOME);
                        Log.i(TAG, "WYN_READY_CONFIRMED; single HOME action accepted=" + accepted
                                + "; elapsedMs=" + elapsed
                                + "; contentEvents=" + contentEvents
                                + "; accessibilityNodes=" + tree.nodeCount
                                + "; loadingIndicator=false");
                        clearPending();
                        return;
                    }
                } else {
                    stableFingerprint = null;
                    stableSince = 0L;
                    if (now - lastEventLog >= 5000L) {
                        lastEventLog = now;
                        Log.i(TAG, "WYN_NOT_READY elapsedMs=" + elapsed
                                + " contentEvents=" + contentEvents
                                + " nodes=" + tree.nodeCount
                                + " loadingIndicator=" + tree.loading
                                + "; keep WYN foreground");
                    }
                }

                if (elapsed >= MAX_WAIT_MS) {
                    Log.w(TAG, "WYN readiness could not be verified within "
                            + MAX_WAIT_MS + " ms; leave WYN foreground for safety");
                    clearPending();
                    return;
                }
                scheduleCheck(CHECK_INTERVAL_MS);
            } catch (Throwable error) {
                Log.w(TAG, "WYN readiness inspection failed; keep waiting", error);
                scheduleCheck(CHECK_INTERVAL_MS);
            } finally {
                if (root != null) root.recycle();
            }
        }
    };

    private static final class TreeState {
        final int nodeCount;
        final boolean loading;
        final String fingerprint;
        TreeState(int nodeCount, boolean loading, String fingerprint) {
            this.nodeCount = nodeCount;
            this.loading = loading;
            this.fingerprint = fingerprint;
        }
    }

    private TreeState inspectTree(AccessibilityNodeInfo root) {
        StringBuilder signature = new StringBuilder(512);
        int[] count = new int[] { 0 };
        boolean[] loading = new boolean[] { false };
        appendNode(root, signature, count, loading);
        return new TreeState(count[0], loading[0],
                Integer.toHexString(signature.toString().hashCode()));
    }

    private void appendNode(AccessibilityNodeInfo node, StringBuilder signature,
                            int[] count, boolean[] loading) {
        if (node == null || count[0] >= MAX_TREE_NODES) return;
        count[0]++;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        CharSequence className = node.getClassName();
        String textValue = text == null ? "" : text.toString();
        String descriptionValue = description == null ? "" : description.toString();
        String classValue = className == null ? "" : className.toString();

        // Fingerprint structure rather than live text values: WYN's speed display
        // can change continuously even after its Home/data layer is ready.
        signature.append(classValue).append('|').append(node.isClickable()).append('|')
                .append(node.isScrollable()).append('|').append(!textValue.isEmpty()).append('|')
                .append(!descriptionValue.isEmpty()).append('|')
                .append(node.getChildCount()).append(';');
        if (containsLoadingText(textValue) || containsLoadingText(descriptionValue)
                || classValue.contains("ProgressBar")) loading[0] = true;

        int children = node.getChildCount();
        for (int i = 0; i < children && count[0] < MAX_TREE_NODES; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try { appendNode(child, signature, count, loading); }
                finally { child.recycle(); }
            }
        }
    }

    private boolean containsLoadingText(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("loading") || normalized.contains("đang tải")
                || normalized.contains("khởi tạo") || normalized.contains("connecting")
                || normalized.contains("đang kết nối") || normalized.contains("please wait")
                || normalized.contains("vui lòng chờ") || normalized.contains("synchronizing")
                || normalized.contains("đồng bộ");
    }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "WYN Accessibility connected; readiness requires a settled Home tree, not Activity resume");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null
                || !WYN_PACKAGE.contentEquals(event.getPackageName())) return;
        long now = System.currentTimeMillis();
        if (wynFirstSeenAt == 0L && isPending()) wynFirstSeenAt = now;
        int type = event.getEventType();
        // Count all WYN UI lifecycle/content events delivered by the declared service
        // filter, not just content changes; Flutter may report readiness through
        // window-state events while its SurfaceView owns most visual rendering.
        contentEvents++;

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
            scheduleCheck(CHECK_INTERVAL_MS);
        }
    }

    private void scheduleCheck(long delayMs) {
        if (checkScheduled || !isPending()) return;
        checkScheduled = true;
        main.postDelayed(readinessCheck, delayMs);
    }

    private boolean isPending() {
        return getSharedPreferences("wyn_auto_start", MODE_PRIVATE)
                .getBoolean("background_after_load", false);
    }

    private void clearPending() {
        main.removeCallbacks(readinessCheck);
        checkScheduled = false;
        getSharedPreferences("wyn_auto_start", MODE_PRIVATE).edit()
                .putBoolean("background_after_load", false)
                .remove("requested_at").commit();
        wynFirstSeenAt = 0L;
        contentEvents = 0;
        stableSince = 0L;
        stableFingerprint = null;
        homeActionRequested = false;
    }

    @Override public void onInterrupt() {
        main.removeCallbacks(readinessCheck);
        checkScheduled = false;
    }
}
