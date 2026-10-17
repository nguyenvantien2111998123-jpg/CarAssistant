package com.carassistant.v10;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.util.Log;

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
    private static final long MAX_PENDING_MS = 25_000L;
    private static final int MAX_DEPTH = 12;

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
        if (root == null) return;
        try {
            if ("search".equals(phase)) {
                AccessibilityNodeInfo result = findSearchResult(root, query);
                if (result != null && clickNodeOrClickableParent(result)) {
                    prefs.edit().putString("phase", "play").apply();
                    Log.i(TAG, "Selected a matching search result in " + eventPackage);
                }
            } else if ("play".equals(phase)) {
                AccessibilityNodeInfo play = findPlayControl(root);
                if (play != null && clickNodeOrClickableParent(play)) {
                    clearPending(prefs);
                    Log.i(TAG, "Tapped a visible play control in " + eventPackage);
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Accessibility media automation failed", error);
        } finally {
            root.recycle();
        }
    }

    private AccessibilityNodeInfo findSearchResult(AccessibilityNodeInfo root, String query) {
        String[] tokens = query.toLowerCase(Locale.ROOT).split("\\s+");
        int requiredMatches = Math.min(2, tokens.length);
        Candidate best = new Candidate();
        collectSearchCandidates(root, tokens, requiredMatches, 0, best);
        return best.node;
    }

    private void collectSearchCandidates(AccessibilityNodeInfo node, String[] tokens,
                                         int requiredMatches, int depth, Candidate best) {
        if (node == null || depth > MAX_DEPTH) return;
        CharSequence className = node.getClassName();
        boolean editable = className != null && className.toString().toLowerCase(Locale.ROOT).contains("edittext");
        String text = com.carassistant.v10.voice.VoiceCommandParser.normalize(
                collectText(node, depth, new StringBuilder()).toString());
        if (!editable && !text.isEmpty() && text.length() > 3 && node.isClickable()) {
            int matches = 0;
            for (String token : tokens) {
                if (token.length() >= 2 && text.contains(token)) matches++;
            }
            // Ignore the search box/suggestion that merely repeats the query.
            if (matches >= requiredMatches && text.split("\\s+").length > tokens.length
                    && matches * 100 + Math.min(text.length(), 99) > best.score) {
                best.score = matches * 100 + Math.min(text.length(), 99);
                best.node = AccessibilityNodeInfo.obtain(node);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectSearchCandidates(child, tokens, requiredMatches, depth + 1, best);
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
            boolean playLabel = normalized.contains("play") || normalized.contains("phát")
                    || normalized.contains("phat") || normalized.contains("tiếp tục")
                    || normalized.contains("tiep tuc") || normalized.contains("resume")
                    || normalized.contains("watch now");
            if (playLabel && node.isClickable()) {
                for (AccessibilityNodeInfo other : nodes) {
                    if (other != node) other.recycle();
                }
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
                if (current.isClickable()) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
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
        // On multi-display devices the active window may belong to the phone or car host.
        // Search interactive windows for the actual media package before giving up.
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window == null) continue;
                AccessibilityNodeInfo candidate = window.getRoot();
                if (candidate == null) continue;
                CharSequence packageName = candidate.getPackageName();
                if (packageName != null && targetPackage.contentEquals(packageName)) {
                    return candidate;
                }
                candidate.recycle();
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot inspect interactive windows", error);
        }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) {
            CharSequence packageName = active.getPackageName();
            if (packageName != null && targetPackage.contentEquals(packageName)) return active;
            active.recycle();
        }
        return null;
    }

    private void clearPending(android.content.SharedPreferences prefs) {
        prefs.edit().remove("target_package").remove("query")
                .remove("phase").remove("started_at").apply();
    }

    @Override public void onInterrupt() { }
}
