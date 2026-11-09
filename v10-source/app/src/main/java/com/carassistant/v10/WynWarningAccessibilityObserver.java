package com.carassistant.v10;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirrors WYN's exposed home-screen warning semantics into two independent
 * channels. WYN remains responsible for GPS, selection, timing and speech.
 */
final class WynWarningAccessibilityObserver {
    private static final String WYN_PACKAGE = "com.wynauto.wyn";
    private static final int MAX_DEPTH = 14;
    private static final long MIN_SCAN_INTERVAL_MS = 180L;
    private static final int EMPTY_ALERT_SCANS_TO_CLEAR = 3;

    private static final Pattern SIGN_CODE = Pattern.compile(
            "(?i)(?:road[_ ]?sign[_ ]?)?\\b(DP|P|R)\\s*[-.]?\\s*(\\d{2,3}[a-z]?\\d?)\\b");
    private static final Pattern SPEED_LIMIT = Pattern.compile(
            "(?i)(?:speed\\s*limit|speedLimit|tốc\\s*độ(?:\\s*tối\\s*đa)?|"
                    + "giới\\s*hạn(?:\\s*tốc\\s*độ)?|biển\\s*tốc\\s*độ)"
                    + "[^0-9]{0,24}(\\d{2,3})");
    private static final Pattern SPEED_WITH_UNIT = Pattern.compile(
            "(?i)(?<!\\d)(\\d{2,3})\\s*(?:km\\s*/?\\s*h|km/giờ)\\b");

    private static volatile long lastScanAt;
    private static volatile String lastSpeedKey = "";
    private static volatile String lastAlertKey = "";
    private static int consecutiveNoAlertScans;

    private WynWarningAccessibilityObserver() { }

    static void observe(AccessibilityService service, AccessibilityEvent event) {
        if (service == null || event == null || event.getPackageName() == null
                || !WYN_PACKAGE.contentEquals(event.getPackageName())) return;

        long now = SystemClock.uptimeMillis();
        if (now - lastScanAt < MIN_SCAN_INTERVAL_MS) return;
        lastScanAt = now;

        AccessibilityNodeInfo root = null;
        boolean scannedWynHome = false;
        try {
            Candidate candidate = new Candidate();
            root = service.getRootInActiveWindow();
            if (root != null && WYN_PACKAGE.contentEquals(value(root.getPackageName()))) {
                scannedWynHome = true;
                scan(root, 0, candidate);
            } else {
                if (root != null) {
                    root.recycle();
                    root = null;
                }
                AccessibilityNodeInfo source = event.getSource();
                if (source != null) {
                    try {
                        if (WYN_PACKAGE.contentEquals(value(source.getPackageName()))) {
                            scan(source, 0, candidate);
                        }
                    } finally {
                        source.recycle();
                    }
                }
            }

            if (candidate.speedKey == null && candidate.alertKey == null) {
                StringBuilder eventText = new StringBuilder();
                for (CharSequence item : event.getText()) {
                    if (item != null) eventText.append(' ').append(item);
                }
                CharSequence description = event.getContentDescription();
                if (description != null) eventText.append(' ').append(description);
                consider(eventText.toString(), candidate);
            }

            publishSpeed(service, candidate.speedKey);
            publishAlert(service, candidate.alertKey);

            // Only clear after repeated complete scans of WYN's own home window
            // confirm that a speed sign remains but no temporary warning is exposed.
            // A missing/hidden window is not evidence that a warning has expired.
            if (scannedWynHome && candidate.speedKey != null
                    && candidate.alertKey == null) {
                consecutiveNoAlertScans++;
                if (consecutiveNoAlertScans >= EMPTY_ALERT_SCANS_TO_CLEAR) {
                    MediaSessionBridge.clearWynTemporaryAlert();
                    lastAlertKey = "";
                }
            } else if (candidate.alertKey != null || !scannedWynHome) {
                consecutiveNoAlertScans = 0;
            }
        } catch (RuntimeException ignored) {
            // Accessibility windows can disappear while WYN changes pages.
        } finally {
            if (root != null) root.recycle();
        }
    }

    private static void publishSpeed(AccessibilityService service, String key) {
        if (TextUtils.isEmpty(key) || key.equals(lastSpeedKey)) return;
        Bitmap bitmap = WynWarningAssetLoader.load(service, key);
        if (bitmap != null) {
            lastSpeedKey = key;
            MediaSessionBridge.setWynSpeedSignBitmap(bitmap);
        }
    }

    private static void publishAlert(AccessibilityService service, String key) {
        if (TextUtils.isEmpty(key)) return;
        consecutiveNoAlertScans = 0;
        if (key.equals(lastAlertKey)) return;
        Bitmap bitmap = WynWarningAssetLoader.load(service, key);
        if (bitmap != null) {
            lastAlertKey = key;
            MediaSessionBridge.setWynTemporaryAlertBitmap(bitmap);
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

        Matcher speed = SPEED_LIMIT.matcher(label);
        if (speed.find()) {
            best.speedKey = "speed:" + speed.group(1);
        } else {
            Matcher speedWithUnit = SPEED_WITH_UNIT.matcher(label);
            if (speedWithUnit.find()) best.speedKey = "speed:" + speedWithUnit.group(1);
        }

        Matcher sign = SIGN_CODE.matcher(label);
        if (sign.find()) {
            String code = (sign.group(1) + sign.group(2))
                    .replaceAll("[^A-Za-z0-9]", "");
            best.alertKey = "road_sign_" + code;
            return;
        }

        if (lower.contains("trafficlightid") || lower.contains("traffic_light")
                || lower.contains("traffic light") || lower.contains("đèn giao thông")
                || lower.contains("đèn tín hiệu")) {
            best.alertKey = "traffic_light";
            return;
        }

        if (lower.contains("speed camera") || lower.contains("speed_camera")
                || lower.contains("camera tốc độ") || lower.contains("camera phạt nguội")
                || lower.contains("traffic camera")) {
            best.alertKey = "speed_camera";
        }
    }

    private static String value(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static final class Candidate {
        String speedKey;
        String alertKey;
    }
}
