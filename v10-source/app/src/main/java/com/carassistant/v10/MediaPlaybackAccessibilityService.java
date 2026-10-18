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
    private final Handler automationHandler = new Handler(Looper.getMainLooper());

    private final Runnable playRetry = new Runnable() {
        @Override public void run() {
            android.content.SharedPreferences prefs =
                    getSharedPreferences(PREFS, MODE_PRIVATE);
            String targetPackage = prefs.getString("target_package", "");
            String phase = prefs.getString("phase", "");
            long startedAt = prefs.getLong("started_at", 0L);
            if (targetPackage.isEmpty() || !"play".equals(phase)) return;
            if (startedAt <= 0L || System.currentTimeMillis() - startedAt > MAX_PENDING_MS) {
                clearPending(prefs);
                Log.w(TAG, "Auto-play timed out for " + targetPackage);
                return;
            }

            AccessibilityNodeInfo root = findRootForPackage(targetPackage);
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
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String eventPackage = event.getPackageName().toString();
        android.content.SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);
        String targetPackage = prefs.getString("target_package", "");
        String query = prefs.getString("query", "");
        String phase = prefs.getString("phase", "");
        long startedAt = prefs.getLong("started_at", 0L);

        if (targetPackage.isEmpty() || query.isEmpty() || phase.isEmpty()) return;
        if (!targetPackage.equals(eventPackage)) return;
        if (startedAt <= 0L || System.currentTimeMillis() - startedAt > MAX_PENDING_MS) {
            clearPending(prefs);
            return;
        }

        AccessibilityNodeInfo root = findRootForPackage(eventPackage);
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

    private AccessibilityNodeInfo findRootForPackage(String targetPackage) {
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
            if (packageName != null && targetPackage.contentEquals(packageName)) return active;
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
        prefs.edit().remove("target_package").remove("query")
                .remove("phase").remove("started_at").apply();
    }

    @Override public void onInterrupt() {
        automationHandler.removeCallbacks(playRetry);
    }

    @Override public void onDestroy() {
        automationHandler.removeCallbacks(playRetry);
        super.onDestroy();
    }
}
