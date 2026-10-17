package com.carassistant.v10.voice;

import java.net.URLEncoder;
import java.util.Locale;

/**
 * Provider-specific media search links. This class is deliberately pure Java so routing
 * can be covered by local unit tests without Android framework stubs.
 */
public final class MediaSearchUriBuilder {
    private MediaSearchUriBuilder() { }

    public static String build(String packageName, String query, String category) {
        if (packageName == null || packageName.trim().isEmpty()
                || query == null || query.trim().isEmpty()) return null;

        String pkg = packageName.trim().toLowerCase(Locale.ROOT);
        final String encoded;
        try {
            // Use the String/encoding-name overload for Android 10+ runtime compatibility.
            encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return null;
        }

        // Official, ReVanced and Morphe YouTube Music package families.
        if (isYouTubeMusicPackage(pkg)) {
            return "https://music.youtube.com/search?q=" + encoded;
        }
        if (isYouTubeVideoPackage(pkg)) {
            return "https://www.youtube.com/results?search_query=" + encoded;
        }
        if (pkg.equals("com.spotify.music") || pkg.contains("spotify")) {
            return "spotify:search:" + encoded;
        }
        if (pkg.contains("zing")) {
            return "https://zingmp3.vn/tim-kiem/tat-ca?q=" + encoded;
        }
        if (pkg.equals("com.nct") || pkg.contains("nhaccuatui")) {
            return "https://www.nhaccuatui.com/tim-kiem?q=" + encoded;
        }

        // Don't guess a URL for an unknown provider. The caller can explain that the
        // selected app has no known search integration instead of opening the wrong app.
        return null;
    }

    public static boolean isYouTubeMusicPackage(String packageName) {
        if (packageName == null) return false;
        String pkg = packageName.toLowerCase(Locale.ROOT);
        return pkg.equals("com.google.android.apps.youtube.music")
                || pkg.contains("youtube.music")
                || pkg.contains("youtubemusic");
    }

    public static boolean isYouTubeVideoPackage(String packageName) {
        if (packageName == null) return false;
        String pkg = packageName.toLowerCase(Locale.ROOT);
        return !isYouTubeMusicPackage(pkg) && pkg.contains("youtube");
    }
}
