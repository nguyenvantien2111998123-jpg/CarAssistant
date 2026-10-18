package com.carassistant.v10;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.util.Log;
import android.util.SparseArray;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Best-effort media auto-play fallback for apps that expose their search results
 * through Android accessibility. The user must explicitly enable this service.
 */
public final class MediaPlaybackAccessibilityService extends AccessibilityService {
    private static final String TAG = "CarAsstMediaA11y";
    private static final String PREFS = "media_autoplay";
    private static final long MAX_PENDING_MS = 30_000L;
    private static final int MAX_DEPTH = 12;
    private static final long RETRY_INTERVAL_MS = 400L;
    private final Handler automationHandler = new Handler(Looper.getMainLooper());
    private long lastWindowSnapshotAt; // Rate-limit diagnostic snapshots while UI events are noisy.

    private final Runnable playRetry = new Runnable() {
        @Override public void run() {
            android.content.SharedPreferences prefs =
                    getSharedPreferences(PREFS, MODE_PRIVATE);
            String targetPackage = prefs.getString("target_package", "");
            String phase = prefs.getString("phase", "");
            int targetDisplayId = prefs.getInt("target_display_id", -1);
            long startedAt = prefs.getLong("started_at", 0L);
            if (targetPackage.isEmpty() || !"play".equals(phase)) return;
            if (startedAt <= 0L || System.currentTimeMillis() - startedAt > MAX_PENDING_MS) {
                clearPending(prefs);
                Log.w(TAG, "Auto-play timed out for " + targetPackage);
                return;
            }

            dumpAccessibilityWindowSnapshot("play-retry", targetPackage, targetDisplayId, false);
            AccessibilityNodeInfo root = findRootForPackage(targetPackage, targetDisplayId);
            if (root != null) {
                try {
                    AccessibilityNodeInfo play = findPlayControl(root);
                    if (play != null) {
                        boolean clicked = clickNodeOrClickableParent(play);
                        if (clicked) {
                            clearPending(prefs);
                            Log.i(TAG, "Tapped a visible Play control in " + targetPackage);
                            root.recycle();
                            return;
                        }
                    }
                } catch (RuntimeException error) {
                    Log.w(TAG, "Play retry failed", error);
                }
                root.recycle();
            }
            automationHandler.postDelayed(this, 400L);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "DIAG serviceConnected sdk=" + Build.VERSION.SDK_INT
                + " flags=" + getServiceInfo().flags
                + " canRetrieveWindowContent=" + getServiceInfo().getCanRetrieveWindowContent());
        dumpAccessibilityWindowSnapshot("service-connected", "", -1, true);
    }

    /**
     * Diagnostic only: record what Android actually exposes to this service.
     * Never logs visible text or spoken queries; only package/class and node counts.
     */
    private void dumpAccessibilityWindowSnapshot(
            String reason, String targetPackage, int targetDisplayId, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastWindowSnapshotAt < 1500L) return;
        lastWindowSnapshotAt = now;
        Log.i(TAG, "DIAG windows begin reason=" + reason
                + " targetPackage=" + targetPackage
                + " targetDisplayId=" + targetDisplayId
                + " sdk=" + Build.VERSION.SDK_INT);
        int windowCount = 0;
        int targetPackageWindows = 0;
        int targetDisplayWindows = 0;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                SparseArray<List<AccessibilityWindowInfo>> displays = getWindowsOnAllDisplays();
                if (displays == null) {
                    Log.w(TAG, "DIAG getWindowsOnAllDisplays returned null");
                } else {
                    for (int displayIndex = 0; displayIndex < displays.size(); displayIndex++) {
                        int arrayDisplayId = displays.keyAt(displayIndex);
                        List<AccessibilityWindowInfo> windows = displays.valueAt(displayIndex);
                        if (windows == null) continue;
                        for (AccessibilityWindowInfo window : windows) {
                            if (window == null) continue;
                            windowCount++;
                            AccessibilityNodeInfo root = null;
                            try {
                                int actualDisplayId = window.getDisplayId();
                                root = window.getRoot();
                                String rootPackage = root == null || root.getPackageName() == null
                                        ? "<no-root-package>" : root.getPackageName().toString();
                                String rootClass = root == null || root.getClassName() == null
                                        ? "<no-root-class>" : root.getClassName().toString();
                                int[] counts = new int[4]; // nodes, text-bearing, clickable, editable
                                if (root != null) summarizeNode(root, 0, counts);
                                if (!targetPackage.isEmpty() && targetPackage.equals(rootPackage)) {
                                    targetPackageWindows++;
                                    if (targetDisplayId < 0 || targetDisplayId == actualDisplayId) {
                                        targetDisplayWindows++;
                                    }
                                }
                                Log.i(TAG, "DIAG window arrayDisplay=" + arrayDisplayId
                                        + " windowDisplay=" + actualDisplayId
                                        + " windowId=" + window.getId()
                                        + " type=" + window.getType()
                                        + " active=" + window.isActive()
                                        + " focused=" + window.isFocused()
                                        + " rootAvailable=" + (root != null)
                                        + " rootPackage=" + rootPackage
                                        + " rootClass=" + rootClass
                                        + " nodes=" + counts[0]
                                        + " textNodes=" + counts[1]
                                        + " clickableNodes=" + counts[2]
                                        + " editableNodes=" + counts[3]);
                            } catch (RuntimeException error) {
                                Log.w(TAG, "DIAG window inspect failed display=" + arrayDisplayId, error);
                            } finally {
                                if (root != null) root.recycle();
                                window.recycle();
                            }
                        }
                    }
                }
            } else {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo window : windows) {
                        if (window == null) continue;
                        windowCount++;
                        AccessibilityNodeInfo root = null;
                        try {
                            root = window.getRoot();
                            String rootPackage = root == null || root.getPackageName() == null
                                    ? "<no-root-package>" : root.getPackageName().toString();
                            String rootClass = root == null || root.getClassName() == null
                                    ? "<no-root-class>" : root.getClassName().toString();
                            int[] counts = new int[4];
                            if (root != null) summarizeNode(root, 0, counts);
                            if (!targetPackage.isEmpty() && targetPackage.equals(rootPackage)) {
                                targetPackageWindows++;
                            }
                            Log.i(TAG, "DIAG window display=<API<30> windowId=" + window.getId()
                                    + " type=" + window.getType()
                                    + " active=" + window.isActive()
                                    + " focused=" + window.isFocused()
                                    + " rootAvailable=" + (root != null)
                                    + " rootPackage=" + rootPackage
                                    + " rootClass=" + rootClass
                                    + " nodes=" + counts[0]
                                    + " textNodes=" + counts[1]
                                    + " clickableNodes=" + counts[2]
                                    + " editableNodes=" + counts[3]);
                        } catch (RuntimeException error) {
                            Log.w(TAG, "DIAG window inspect failed", error);
                        } finally {
                            if (root != null) root.recycle();
                            window.recycle();
                        }
                    }
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "DIAG cannot enumerate accessibility windows", error);
        }
        Log.i(TAG, "DIAG windows end count=" + windowCount
                + " targetPackageWindows=" + targetPackageWindows
                + " targetDisplayWindows=" + targetDisplayWindows);
    }

    private void summarizeNode(AccessibilityNodeInfo node, int depth, int[] counts) {
        if (node == null || depth > 10 || counts[0] >= 1200) return;
        counts[0]++;
        if ((node.getText() != null && node.getText().length() > 0)
                || (node.getContentDescription() != null
                && node.getContentDescription().length() > 0)) counts[1]++;
        if (node.isClickable()) counts[2]++;
        if (node.isEditable()) counts[3]++;
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount && counts[0] < 1200; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                if (child != null) summarizeNode(child, depth + 1, counts);
            } catch (RuntimeException ignored) {
                // A provider can invalidate nodes while the UI is changing.
            } finally {
                if (child != null) child.recycle();
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        String eventPackage = event.getPackageName() == null
                ? "" : event.getPackageName().toString();
        android.content.SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);
        String targetPackage = prefs.getString("target_package", "");
        String query = prefs.getString("query", "");
        String phase = prefs.getString("phase", "");
        int targetDisplayId = prefs.getInt("target_display_id", -1);
        long startedAt = prefs.getLong("started_at", 0L);

        if (targetPackage.isEmpty() || query.isEmpty() || phase.isEmpty()) return;
        // Snapshot on events from ANY package while a diagnostic/autoplay request is pending.
        // This still produces evidence if the projected app itself emits no accessibility event.
        dumpAccessibilityWindowSnapshot("event-" + event.getEventType(),
                targetPackage, targetDisplayId, false);
        if (!targetPackage.equals(eventPackage)) return;
        AccessibilityNodeInfo eventSourceForDiag = event.getSource();
        if (eventSourceForDiag != null) {
            try {
                AccessibilityWindowInfo sourceWindow = eventSourceForDiag.getWindow();
                if (sourceWindow != null) {
                    try {
                        Log.i(TAG, "DIAG event sourcePackage=" + eventPackage
                                + " eventType=" + event.getEventType()
                                + " sourceWindowId=" + sourceWindow.getId()
                                + " sourceDisplayId=" + (Build.VERSION.SDK_INT >= 30
                                        ? sourceWindow.getDisplayId() : -1)
                                + " targetDisplayId=" + targetDisplayId);
                    } finally {
                        sourceWindow.recycle();
                    }
                } else {
                    Log.i(TAG, "DIAG event source has no window package=" + eventPackage
                            + " eventType=" + event.getEventType());
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "DIAG cannot inspect event source window", error);
            } finally {
                eventSourceForDiag.recycle();
            }
        } else {
            Log.i(TAG, "DIAG event has no source package=" + eventPackage
                    + " eventType=" + event.getEventType());
        }
        if (startedAt <= 0L || System.currentTimeMillis() - startedAt > MAX_PENDING_MS) {
            clearPending(prefs);
            return;
        }

        AccessibilityNodeInfo root = findRootFromEvent(event, eventPackage, targetDisplayId);
        if (root == null) {
            root = findRootForPackage(eventPackage, targetDisplayId);
        }
        if (root == null) {
            // The media app may be opening on a non-default display. Window events can
            // arrive before its accessibility root is exposed; retry on the next event.
            Log.d(TAG, "No accessibility root yet for " + eventPackage
                    + ", eventType=" + event.getEventType());
            return;
        }
        try {
            if ("search".equals(phase)) {
                AccessibilityNodeInfo result = findSearchResult(root, query);
                if (result != null) {
                    boolean clicked = clickNodeOrClickableParent(result);
                    if (clicked) {
                        prefs.edit().putString("phase", "play").apply();
                        automationHandler.removeCallbacks(playRetry);
                        automationHandler.postDelayed(playRetry, 500L);
                        Log.i(TAG, "Selected a matching search result in " + eventPackage);
                    } else {
                        Log.d(TAG, "Matching result was found but click was rejected in "
                                + eventPackage);
                    }
                }
            } else if ("play".equals(phase)) {
                automationHandler.removeCallbacks(playRetry);
                automationHandler.post(playRetry);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Accessibility media automation failed", error);
        } finally {
            root.recycle();
        }
    }

    /**
     * Prefer the smallest clickable result card that matches the spoken query.
     * The previous scoring rewarded the largest text subtree, which could select a
     * page/list container instead of an individual song or video result.
     */
    private AccessibilityNodeInfo findSearchResult(AccessibilityNodeInfo root, String query) {
        String normalizedQuery =
                com.carassistant.v10.voice.VoiceCommandParser.normalize(query);
        String[] tokens = normalizedQuery.split("\\s+");
        int requiredMatches = Math.min(2, tokens.length);
        Candidate best = new Candidate();
        collectSearchCandidates(root, normalizedQuery, tokens, requiredMatches, 0, best);
        return best.node;
    }

    private void collectSearchCandidates(AccessibilityNodeInfo node, String normalizedQuery,
                                         String[] tokens, int requiredMatches,
                                         int depth, Candidate best) {
        if (node == null || depth > MAX_DEPTH) return;
        CharSequence className = node.getClassName();
        boolean editable = className != null
                && className.toString().toLowerCase(Locale.ROOT).contains("edittext");
        String normalizedText = com.carassistant.v10.voice.VoiceCommandParser.normalize(
                collectText(node, depth, new StringBuilder()).toString());
        if (!editable && !normalizedText.isEmpty() && node.isClickable()) {
            int matches = 0;
            for (String token : tokens) {
                if (token.length() >= 2 && normalizedText.contains(token)) matches++;
            }
            int wordCount = normalizedText.split("\\s+").length;
            boolean includesWholeQuery = !normalizedQuery.isEmpty()
                    && normalizedText.contains(normalizedQuery);
            // Avoid the search field/suggestion that only echoes the query. A real result
            // normally includes a title plus artist, channel, duration, or other metadata.
            if (matches >= requiredMatches && wordCount > tokens.length) {
                // More query matches and a full phrase are better; among those, prefer the
                // shortest matching clickable subtree so a page container is not tapped.
                int score = matches * 1000
                        + (includesWholeQuery ? 500 : 0)
                        - Math.min(normalizedText.length(), 700);
                if (score > best.score) {
                    if (best.node != null) best.node.recycle();
                    best.score = score;
                    best.node = AccessibilityNodeInfo.obtain(node);
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectSearchCandidates(child, normalizedQuery, tokens, requiredMatches,
                        depth + 1, best);
                child.recycle();
            }
        }
    }

    private StringBuilder collectText(AccessibilityNodeInfo node, int depth, StringBuilder out) {
        if (node == null || depth > MAX_DEPTH) return out;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if (text != null) out.append(' ').append(text);
        if (description != null) out.append(' ').append(description);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectText(child, depth + 1, out);
                child.recycle();
            }
        }
        return out;
    }

    private AccessibilityNodeInfo findPlayControl(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, 0, nodes);
        for (AccessibilityNodeInfo node : nodes) {
            String text = "";
            if (node.getText() != null) text += node.getText().toString();
            if (node.getContentDescription() != null) text += " " + node.getContentDescription();
            String normalized = com.carassistant.v10.voice.VoiceCommandParser.normalize(text);
            String viewId = node.getViewIdResourceName();
            String normalizedId = com.carassistant.v10.voice.VoiceCommandParser.normalize(viewId);
            boolean pauseState = normalized.contains("pause")
                    || normalized.contains("tạm dừng")
                    || normalized.contains("tam dung")
                    || normalized.contains("stop");
            boolean playLabel = normalized.contains("play") || normalized.contains("phát")
                    || normalized.contains("phat") || normalized.contains("tiếp tục")
                    || normalized.contains("tiep tuc") || normalized.contains("resume")
                    || normalized.contains("watch now");
            boolean playId = normalizedId.contains("play")
                    && !normalizedId.contains("pause")
                    && !normalizedId.contains("play pause")
                    && !normalizedId.contains("playpause");
            if (!pauseState && (playLabel || playId)) {
                for (AccessibilityNodeInfo other : nodes) {
                    if (other != node) other.recycle();
                }
                // The icon itself may not be clickable; clickNodeOrClickableParent walks
                // up to the nearest clickable container.
                return node;
            }
        }
        for (AccessibilityNodeInfo node : nodes) node.recycle();
        return null;
    }

    private void collectNodes(AccessibilityNodeInfo node, int depth, List<AccessibilityNodeInfo> out) {
        if (node == null || depth > MAX_DEPTH) return;
        out.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectNodes(child, depth + 1, out);
                child.recycle();
            }
        }
    }

    private boolean clickNodeOrClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        try {
            for (int i = 0; current != null && i < 5; i++) {
                if (current.isClickable()) {
                    boolean clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (clicked) return true;
                }
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
        } finally {
            if (current != null) current.recycle();
            node.recycle();
        }
        return false;
    }

    /**
     * Prefer the source window of the actual accessibility event. On older Android
     * releases the global window list may not expose non-default-display windows,
     * even though the service receives events from those windows.
     */
    private AccessibilityNodeInfo findRootFromEvent(
            AccessibilityEvent event, String targetPackage, int targetDisplayId) {
        AccessibilityNodeInfo current = event == null ? null : event.getSource();
        if (current == null) return null;
        CharSequence sourcePackage = current.getPackageName();
        if (sourcePackage == null || !targetPackage.contentEquals(sourcePackage)) {
            current.recycle();
            return null;
        }

        if (Build.VERSION.SDK_INT >= 30 && targetDisplayId >= 0) {
            AccessibilityWindowInfo sourceWindow = current.getWindow();
            if (sourceWindow != null) {
                try {
                    if (sourceWindow.getDisplayId() != targetDisplayId) {
                        current.recycle();
                        return null;
                    }
                } finally {
                    sourceWindow.recycle();
                }
            }
        }

        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            AccessibilityNodeInfo parent = current.getParent();
            if (parent == null) return current;
            current.recycle();
            current = parent;
        }
        return current;
    }

    private AccessibilityNodeInfo findRootForPackage(String targetPackage, int targetDisplayId) {
        // API 30+ can expose windows from all displays. getWindows() alone may only
        // return the default/active display and miss the app projected to display 2.
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                SparseArray<List<AccessibilityWindowInfo>> displays = getWindowsOnAllDisplays();
                for (int displayIndex = 0; displayIndex < displays.size(); displayIndex++) {
                    List<AccessibilityWindowInfo> windows = displays.valueAt(displayIndex);
                    if (windows == null) continue;
                    for (AccessibilityWindowInfo window : windows) {
                        if (window == null) continue;
                        AccessibilityNodeInfo candidate = null;
                        try {
                            int windowDisplayId = window.getDisplayId();
                            if (targetDisplayId >= 0 && windowDisplayId != targetDisplayId) continue;
                            candidate = window.getRoot();
                            if (candidate != null) {
                                CharSequence packageName = candidate.getPackageName();
                                if (packageName != null && targetPackage.contentEquals(packageName)) {
                                    Log.d(TAG, "Matched accessibility root package=" + targetPackage
                                            + " displayId=" + windowDisplayId);
                                    return candidate;
                                }
                            }
                        } finally {
                            if (candidate != null) {
                                CharSequence packageName = candidate.getPackageName();
                                if (packageName == null || !targetPackage.contentEquals(packageName)) {
                                    candidate.recycle();
                                }
                            }
                            window.recycle();
                        }
                    }
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "Cannot inspect windows on all displays", error);
            }
        } else {
            try {
                for (AccessibilityWindowInfo window : getWindows()) {
                    if (window == null) continue;
                    AccessibilityNodeInfo candidate = null;
                    try {
                        candidate = window.getRoot();
                        if (candidate != null) {
                            CharSequence packageName = candidate.getPackageName();
                            if (packageName != null && targetPackage.contentEquals(packageName)) {
                                return candidate;
                            }
                        }
                    } finally {
                        if (candidate != null) {
                            CharSequence packageName = candidate.getPackageName();
                            if (packageName == null || !targetPackage.contentEquals(packageName)) {
                                candidate.recycle();
                            }
                        }
                        window.recycle();
                    }
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "Cannot inspect interactive windows", error);
            }
        }

        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) {
            CharSequence packageName = active.getPackageName();
            boolean packageMatches = packageName != null && targetPackage.contentEquals(packageName);
            boolean displayMatches = targetDisplayId < 0;
            if (Build.VERSION.SDK_INT >= 30) {
                AccessibilityWindowInfo activeWindow = active.getWindow();
                if (activeWindow != null) {
                    try {
                        displayMatches = targetDisplayId < 0
                                || activeWindow.getDisplayId() == targetDisplayId;
                    } finally {
                        activeWindow.recycle();
                    }
                }
            }
            if (packageMatches && displayMatches) return active;
            active.recycle();
        }
        return null;
    }

    private static final class Candidate {
        AccessibilityNodeInfo node;
        int score;
    }

    private void clearPending(android.content.SharedPreferences prefs) {
        automationHandler.removeCallbacks(playRetry);
        automationHandler.removeCallbacks(searchRetry);
        prefs.edit().remove("target_package").remove("query")
                .remove("phase").remove("target_display_id").remove("started_at").apply();
        automationHandler.postDelayed(searchRetry, 1000L);
    }

    /**
     * Accessibility events are not consistently delivered for projected/non-focused
     * windows on all Android builds. Poll the explicitly selected display while a
     * voice media request is pending, rather than depending on one content-change event.
     */
    private final Runnable searchRetry = new Runnable() {
        @Override public void run() {
            android.content.SharedPreferences prefs =
                    getSharedPreferences(PREFS, MODE_PRIVATE);
            String targetPackage = prefs.getString("target_package", "");
            String query = prefs.getString("query", "");
            String phase = prefs.getString("phase", "");
            int targetDisplayId = prefs.getInt("target_display_id", -1);
            long startedAt = prefs.getLong("started_at", 0L);
            if (targetPackage.isEmpty() || query.isEmpty()) {
                automationHandler.postDelayed(this, 1000L);
                return;
            }
            if (startedAt <= 0L || System.currentTimeMillis() - startedAt > MAX_PENDING_MS) {
                clearPending(prefs);
                Log.w(TAG, "Media automation timed out for " + targetPackage
                        + " displayId=" + targetDisplayId);
                return;
            }
            if (!"search".equals(phase)) {
                automationHandler.postDelayed(this, 1000L);
                return;
            }

            dumpAccessibilityWindowSnapshot("search-retry", targetPackage, targetDisplayId, false);
            AccessibilityNodeInfo root = findRootForPackage(targetPackage, targetDisplayId);
            if (root != null) {
                try {
                    AccessibilityNodeInfo result = findSearchResult(root, query);
                    if (result != null) {
                        boolean clicked = clickNodeOrClickableParent(result);
                        if (clicked) {
                            prefs.edit().putString("phase", "play").apply();
                            Log.i(TAG, "Selected matching result package=" + targetPackage
                                    + " displayId=" + targetDisplayId);
                            automationHandler.removeCallbacks(playRetry);
                            automationHandler.postDelayed(playRetry, 500L);
                            automationHandler.postDelayed(this, 1000L);
                            return;
                        }
                        Log.w(TAG, "Result click rejected package=" + targetPackage
                                + " displayId=" + targetDisplayId);
                    } else {
                        Log.d(TAG, "No matching result yet package=" + targetPackage
                                + " displayId=" + targetDisplayId);
                    }
                } catch (RuntimeException error) {
                    Log.w(TAG, "Search selection retry failed", error);
                } finally {
                    root.recycle();
                }
            } else {
                Log.d(TAG, "No root for package=" + targetPackage
                        + " displayId=" + targetDisplayId);
            }
            automationHandler.postDelayed(this, RETRY_INTERVAL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        automationHandler.removeCallbacks(searchRetry);
        automationHandler.post(searchRetry);
    }

    @Override public void onInterrupt() {
        automationHandler.removeCallbacks(playRetry);
        automationHandler.removeCallbacks(searchRetry);
    }

    @Override public void onDestroy() {
        automationHandler.removeCallbacks(playRetry);
        automationHandler.removeCallbacks(searchRetry);
        super.onDestroy();
    }
}
