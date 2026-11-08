package com.carassistant.v10;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads only WYN's exposed accessibility semantics. WYN remains responsible for
 * GPS, warning selection, spoken alerts and sequencing; this class mirrors its
 * current sign asset and never calculates a warning from location.
 */
final class WynWarningAccessibilityObserver {
    private static final String WYN_PACKAGE = "com.wynauto.wyn";
    private static final int MAX_DEPTH = 14;
    private static final long MIN_SCAN_INTERVAL_MS = 180L;
    private static final Pattern SIGN_CODE = Pattern.compile(
            "(?i)(?:road[_ ]?sign[_ ]?)?\\b(DP|P|R)\\s*[-.]?\\s*(\\d{2,3}[a-z]?)\\b");
    private static final Pattern SPEED_LIMIT = Pattern.compile(
            "(?i)(?:speed\\s*limit|limit|tốc\\s*độ(?:\\s*tối\\s*đa)?|"
                    + "giới\\s*hạn(?:\\s*tốc\\s*độ)?|biển\\s*tốc\\s*độ)"
                    + "[^0-9]{0,24}(\\d{2,3})");
    private static volatile long lastScanAt;
    private static volatile String lastPublishedKey = "";

    private WynWarningAccessibilityObserver() { }

    static void observe(AccessibilityService service, AccessibilityEvent event) {
        if (service == null || event == null || event.getPackageName() == null
                || !WYN_PACKAGE.contentEquals(event.getPackageName())) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastScanAt < MIN_SCAN_INTERVAL_MS) return;
        lastScanAt = now;

        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            if (root == null || root.getPackageName() == null
                    || !WYN_PACKAGE.contentEquals(root.getPackageName())) return;
            Candidate candidate = new Candidate();
            scan(root, 0, candidate);
            if (TextUtils.isEmpty(candidate.key)
                    || candidate.key.equals(lastPublishedKey)) return;

            android.graphics.Bitmap bitmap = WynWarningAssetLoader.load(service, candidate.key);
            if (bitmap != null) {
                lastPublishedKey = candidate.key;
                MediaSessionBridge.setWynWarningBitmap(bitmap);
            }
        } catch (RuntimeException ignored) {
            // Accessibility windows can disappear while WYN changes pages.
        } finally {
            if (root != null) root.recycle();
        }
    }

    private static void scan(AccessibilityNodeInfo node, int depth, Candidate best) {
        if (node == null || depth > MAX_DEPTH) return;
        String text = value(node.getText());
        String description = value(node.getContentDescription());
        String viewId = value(node.getViewIdResourceName());
        String combined = (description + " " + text + " " + viewId).trim();
        if (!combined.isEmpty()) consider(combined, best);

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                scan(child, depth + 1, best);
                child.recycle();
            }
        }
    }

    private static void consider(String label, Candidate best) {
        String lower = label.toLowerCase(Locale.ROOT);
        Matcher sign = SIGN_CODE.matcher(label);
        if (sign.find()) {
            String code = (sign.group(1) + sign.group(2)).replaceAll("[^A-Za-z0-9]", "");
            String key = "road_sign_" + code;
            if (best.score < 100) {
                best.key = key;
                best.score = 100;
            }
            return;
        }

        Matcher speed = SPEED_LIMIT.matcher(label);
        if (speed.find()) {
            String key = "speed:" + speed.group(1);
            if (best.score < 90) {
                best.key = key;
                best.score = 90;
            }
            return;
        }

        if (lower.contains("trafficlightid") || lower.contains("traffic light")
                || lower.contains("đèn giao thông") || lower.contains("đèn tín hiệu")) {
            if (best.score < 80) {
                best.key = "traffic_light";
                best.score = 80;
            }
            return;
        }

        if (lower.contains("speed camera") || lower.contains("camera tốc độ")
                || lower.contains("camera phạt nguội")
                || lower.contains("traffic camera")) {
            if (best.score < 70) {
                best.key = "speed_camera";
                best.score = 70;
            }
        }
    }

    private static String value(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static final class Candidate {
        String key = "";
        int score;
    }
}
