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

    private final Runnable playRetry = new Runnable() {
        @Override public void run() {
            android.content.SharedPreferences prefs =
                    getSharedPreferences(PREFS, MODE_PRIVATE);
            String targetPackage = prefs.getString("target_package", "");
            String phase = prefs.getString("phase", "");
            int targetDisplayId = prefs.getInt("target_display_id", -1);
            long startedAt = prefs.getLong("started_at", 0L);
            if (targetPackage.isEmpty()
                    || (!"wait_player".equals(phase) && !"verify".equals(phase))) return;
            long now = System.currentTimeMillis();
            if (startedAt <= 0L || now - startedAt > MAX_PENDING_MS) {
                Log.w(TAG, "Media automation timed out package=" + targetPackage
                        + " displayId=" + targetDisplayId + " phase=" + phase);
                clearPending(prefs);
                return;
            }

            AccessibilityNodeInfo root = findRootForPackage(targetPackage, targetDisplayId);
            if (root != null) {
                try {
                    if (isPlaybackConfirmed(root, prefs, now)) {
                        Log.i(TAG, "Playback verified on player UI package=" + targetPackage
                                + " displayId=" + targetDisplayId);
                        clearPending(prefs);
                        root.recycle();
                        return;
                    }
                    if ("verify".equals(phase)) {
                        long clickedAt = prefs.getLong("play_clicked_at", 0L);
                        if (clickedAt > 0L && now - clickedAt >= 6000L) {
                            Log.w(TAG, "Play click was not verified package=" + targetPackage
                                    + " displayId=" + targetDisplayId);
                            clearPending(prefs);
                            root.recycle();
                            return;
                        }
                    } else {
                        long resultClickedAt = prefs.getLong("result_clicked_at", 0L);
                        if (resultClickedAt > 0L && now - resultClickedAt >= 700L
                                && isPlayerUiReady(root)) {
                            AccessibilityNodeInfo play = findPlayControl(root);
                            if (play != null) {
                                boolean clicked = clickNodeOrClickableParent(play);
                                if (clicked) {
                                    prefs.edit().putString("phase", "verify")
                                            .putLong("play_clicked_at", now).apply();
                                    Log.i(TAG, "Play action accepted; verifying playback package="
                                            + targetPackage + " displayId=" + targetDisplayId);
                                    root.recycle();
                                    automationHandler.postDelayed(this, RETRY_INTERVAL_MS);
                                    return;
                                }
                                Log.d(TAG, "Play control click rejected package=" + targetPackage
                                        + " displayId=" + targetDisplayId);
                            }
                        } else {
                            Log.d(TAG, "Waiting for player UI package=" + targetPackage
                                    + " displayId=" + targetDisplayId);
                        }
                    }
                } catch (RuntimeException error) {
                    Log.w(TAG, "Play retry failed", error);
                }
                root.recycle();
            } else {
                Log.d(TAG, "No player root on target display package=" + targetPackage
                        + " displayId=" + targetDisplayId);
            }
            automationHandler.postDelayed(this, RETRY_INTERVAL_MS);
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
        int targetDisplayId = prefs.getInt("target_display_id", -1);
        long startedAt = prefs.getLong("started_at", 0L);

        if (targetPackage.isEmpty() || query.isEmpty() || phase.isEmpty()) return;
        if (!targetPackage.equals(eventPackage)) return;
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
                        prefs.edit().putString("phase", "wait_player")
                                    .putLong("result_clicked_at", System.currentTimeMillis()).apply();
                        automationHandler.removeCallbacks(playRetry);
                        automationHandler.postDelayed(playRetry, 500L);
                        Log.i(TAG, "Selected a matching search result in " + eventPackage);
                    } else {
                        Log.d(TAG, "Matching result was found but click was rejected in "
                                + eventPackage);
                    }
                }
            } else if ("wait_player".equals(phase) || "verify".equals(phase)) {
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
        String[] tokens = normalizedQuery.trim().isEmpty()
                ? new String[0] : normalizedQuery.trim().split("\\s+");
        if (tokens.length == 0) return null;
        int requiredMatches = Math.min(2, tokens.length);
        CharSequence packageNameValue = root.getPackageName();
        String packageName = packageNameValue == null ? "" : packageNameValue.toString();
        Candidate best = new Candidate();
        collectSearchCandidates(root, normalizedQuery, tokens, requiredMatches,
                packageName, 0, best);
        return best.node;
    }

    private void collectSearchCandidates(AccessibilityNodeInfo node, String normalizedQuery,
                                         String[] tokens, int requiredMatches, String packageName,
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
            // Do not require metadata beyond the query: some apps expose a clickable
            // result whose accessible text is only the exact title/artist. Requiring more
            // words than the query silently rejects those valid results in YouTube,
            // YouTube Music, and Zing MP3. Editable search fields are already excluded above;
            // rank longer result cards higher, but allow an exact-title clickable node.
            if (matches >= requiredMatches && wordCount >= tokens.length) {
                // More query matches and a full phrase are better; among those, prefer the
                // shortest matching clickable subtree so a page container is not tapped.
                // Cap token matches so a giant clickable page container cannot beat
                // the individual result card simply because it contains repeated metadata.
                int score = Math.min(matches, requiredMatches) * 1000
                        + (includesWholeQuery ? 600 : 0)
                        + mediaResultMetadataBonus(normalizedText, packageName)
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
                        packageName, depth + 1, best);
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

    /** Prefer an actionable Play control by ID, then description, then exact label. */
    private AccessibilityNodeInfo findPlayControl(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, 0, nodes);
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        for (AccessibilityNodeInfo node : nodes) {
            String text = node.getText() == null ? "" : node.getText().toString();
            String description = node.getContentDescription() == null
                    ? "" : node.getContentDescription().toString();
            String normalizedText = com.carassistant.v10.voice.VoiceCommandParser.normalize(text);
            String normalizedDescription =
                    com.carassistant.v10.voice.VoiceCommandParser.normalize(description);
            String normalizedId = com.carassistant.v10.voice.VoiceCommandParser.normalize(
                    node.getViewIdResourceName());
            String combined = normalizedText + " " + normalizedDescription;
            boolean pauseState = combined.contains("pause")
                    || combined.contains("tạm dừng") || combined.contains("tam dung");
            boolean playId = normalizedId.contains("play")
                    && !normalizedId.contains("pause") && !normalizedId.contains("playlist")
                    && !normalizedId.contains("play pause") && !normalizedId.contains("playpause");
            boolean playDescription = isExplicitPlayLabel(normalizedDescription);
            boolean playText = isExplicitPlayLabel(normalizedText);
            if (pauseState || !(playId || playDescription || playText)
                    || !hasClickableAncestor(node)) continue;
            int score = playId ? 300 : (playDescription ? 200 : 100);
            if (score > bestScore) {
                if (best != null) best.recycle();
                best = AccessibilityNodeInfo.obtain(node);
                bestScore = score;
            }
        }
        for (AccessibilityNodeInfo node : nodes) node.recycle();
        if (best == null) Log.d(TAG, "No unambiguous actionable Play control in player UI");
        return best;
    }

    private boolean isExplicitPlayLabel(String value) {
        if (value == null || value.isEmpty()) return false;
        return value.equals("play") || value.equals("phát") || value.equals("phat")
                || value.equals("resume") || value.equals("tiếp tục") || value.equals("tiep tuc")
                || value.equals("play video") || value.equals("play song")
                || value.equals("phát video") || value.equals("phát bài hát")
                || value.equals("xem ngay");
    }

    private boolean hasClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        try {
            for (int depth = 0; current != null && depth < 5; depth++) {
                if (current.isClickable()) return true;
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
            return false;
        } finally {
            if (current != null) current.recycle();
        }
    }

    /**
     * Confirm actual player activity rather than treating any Pause-like label on a
     * search preview/card as proof. Prefer a Pause control inside a recognized player
     * surface; otherwise require the player's exposed progress range to move between
     * observations. All inspection stays on the explicitly targeted display root.
     */
    private boolean isPlaybackConfirmed(AccessibilityNodeInfo root,
                                        android.content.SharedPreferences prefs,
                                        long now) {
        if (!isPlayerUiReady(root)) return false;
        if (hasPauseControl(root)) return true;

        float progress = findPlaybackProgress(root);
        if (Float.isNaN(progress)) return false;

        long previousAt = prefs.getLong("progress_sample_at", 0L);
        float previous = prefs.getFloat("progress_sample_value", -1f);
        if (previousAt > 0L && now - previousAt >= 900L
                && previous >= 0f && Math.abs(progress - previous) >= 0.0005f) {
            Log.d(TAG, "Playback progress advanced from " + previous + " to " + progress);
            return true;
        }

        if (previousAt == 0L || now - previousAt >= 900L) {
            prefs.edit().putFloat("progress_sample_value", progress)
                    .putLong("progress_sample_at", now).apply();
        }
        return false;
    }

    /** Returns a normalized 0..1 progress value exposed by a player seek/range control. */
    private float findPlaybackProgress(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, 0, nodes);
        float progress = Float.NaN;
        for (AccessibilityNodeInfo node : nodes) {
            CharSequence classValue = node.getClassName();
            String className = classValue == null ? ""
                    : classValue.toString().toLowerCase(Locale.ROOT);
            String viewId = com.carassistant.v10.voice.VoiceCommandParser.normalize(
                    node.getViewIdResourceName());
            boolean progressControl = className.contains("seekbar")
                    || viewId.contains("seek") || viewId.contains("progress");
            if (progressControl) {
                AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
                if (range != null && range.getMax() > range.getMin()) {
                    progress = (range.getCurrent() - range.getMin())
                            / (range.getMax() - range.getMin());
                    break;
                }
            }
        }
        for (AccessibilityNodeInfo node : nodes) node.recycle();
        return progress;
    }

    /** Small app-specific hints help prefer song/video cards over generic containers. */
    private int mediaResultMetadataBonus(String value, String packageName) {
        int bonus = 0;
        boolean youtubeMusic = packageName.equals("com.google.android.apps.youtube.music")
                || packageName.equals("app.morphe.android.apps.youtube.music");
        boolean youtube = packageName.equals("app.revanced.android.youtube")
                || packageName.equals("app.morphe.android.youtube")
                || packageName.equals("com.google.android.youtube");
        boolean zing = packageName.equals("com.zing.mp3");
        if (youtubeMusic || zing) {
            if (value.contains("artist") || value.contains("nghe si")
                    || value.contains("ca si") || value.contains("bai hat")
                    || value.contains("album") || value.contains("playlist")
                    || value.contains("music video") || value.contains("mv")) bonus += 180;
        }
        if (youtube) {
            if (value.contains("views") || value.contains("luot xem")
                    || value.contains("channel") || value.contains("kenh")
                    || value.contains("ago") || value.contains("phut")
                    || value.contains("video")) bonus += 120;
        }
        return bonus;
    }

    private boolean hasPauseControl(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, 0, nodes);
        boolean found = false;
        for (int i = 0; i < nodes.size(); i++) {
            AccessibilityNodeInfo node = nodes.get(i);
            String text = node.getText() == null ? "" : node.getText().toString();
            String description = node.getContentDescription() == null
                    ? "" : node.getContentDescription().toString();
            String label = com.carassistant.v10.voice.VoiceCommandParser.normalize(
                    text + " " + description);
            String viewId = com.carassistant.v10.voice.VoiceCommandParser.normalize(
                    node.getViewIdResourceName());
            boolean toggleLabel = label.contains("play pause") || label.contains("playpause")
                    || label.contains("pause play") || label.contains("pauseplay");
            boolean pauseLabel = !toggleLabel && (label.contains("pause")
                    || label.contains("tạm dừng") || label.contains("tam dung"));
            boolean toggleId = viewId.contains("play pause") || viewId.contains("playpause")
                    || viewId.contains("pause play") || viewId.contains("pauseplay");
            boolean pauseId = !toggleId && viewId.contains("pause");
            if (pauseLabel || pauseId) found = true;
            node.recycle();
            if (found) {
                for (int j = i + 1; j < nodes.size(); j++) nodes.get(j).recycle();
                break;
            }
        }
        return found;
    }

    /** Require signs of a player surface before clicking a control on a result page. */
    private boolean isPlayerUiReady(AccessibilityNodeInfo root) {
        if (hasPauseControl(root)) return true;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, 0, nodes);
        boolean ready = false;
        for (int i = 0; i < nodes.size(); i++) {
            AccessibilityNodeInfo node = nodes.get(i);
            String className = node.getClassName() == null ? ""
                    : node.getClassName().toString().toLowerCase(Locale.ROOT);
            String text = node.getText() == null ? "" : node.getText().toString();
            String description = node.getContentDescription() == null ? ""
                    : node.getContentDescription().toString();
            String value = com.carassistant.v10.voice.VoiceCommandParser.normalize(
                    text + " " + description + " " + node.getViewIdResourceName());
            String viewId = com.carassistant.v10.voice.VoiceCommandParser.normalize(
                    node.getViewIdResourceName());
            boolean seekControl = className.contains("seekbar")
                    || value.contains("seek bar") || value.contains("seekbar");
            boolean playerLabel = value.contains("now playing") || value.contains("đang phát")
                    || value.contains("dang phat") || value.contains("video player")
                    || value.contains("music player");
            // Some apps (including Compose-based players) expose no standard SeekBar class.
            // Accept player-specific control IDs, but not generic Play labels from result cards.
            boolean playerControlId = viewId.contains("player")
                    && (viewId.contains("play") || viewId.contains("control")
                    || viewId.contains("seek") || viewId.contains("progress"));
            boolean explicitPlayerButtonId = (viewId.contains("play_button")
                    || viewId.contains("playbutton") || viewId.contains("play_pause"))
                    && !viewId.contains("playlist");
            if (seekControl || playerLabel || playerControlId || explicitPlayerButtonId) {
                ready = true;
            }
            node.recycle();
            if (ready) {
                for (int j = i + 1; j < nodes.size(); j++) nodes.get(j).recycle();
                break;
            }
        }
        return ready;
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
                .remove("phase").remove("target_display_id").remove("started_at")
                .remove("result_clicked_at").remove("play_clicked_at")
                .remove("progress_sample_at").remove("progress_sample_value").apply();
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
                if ("wait_player".equals(phase) || "verify".equals(phase)) {
                    automationHandler.removeCallbacks(playRetry);
                    automationHandler.post(playRetry);
                }
                automationHandler.postDelayed(this, 1000L);
                return;
            }

            AccessibilityNodeInfo root = findRootForPackage(targetPackage, targetDisplayId);
            if (root != null) {
                try {
                    AccessibilityNodeInfo result = findSearchResult(root, query);
                    if (result != null) {
                        boolean clicked = clickNodeOrClickableParent(result);
                        if (clicked) {
                            prefs.edit().putString("phase", "wait_player")
                                    .putLong("result_clicked_at", System.currentTimeMillis()).apply();
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
