package com.carassistant.v10.voice;

import java.text.Normalizer;
import java.util.Locale;

/** Deterministic Vietnamese voice command parser for CarAssistant. */
public final class VoiceCommandParser {
    public enum Type {
        OPEN_APP, SHOW_APPS, HOME, CANCEL,
        NAVIGATE, SEARCH_NEARBY, PLAY_MUSIC, PLAY_VIDEO, OPEN_TV,
        UNKNOWN
    }

    public static final class Command {
        public final Type type;
        /** Destination, search query, channel, or app name. */
        public final String argument;
        /** Explicit provider/app spoken after "bằng" or "trên". */
        public final String provider;
        /** navigation, music, video, or tv; empty for general commands. */
        public final String category;

        Command(Type type, String argument) { this(type, argument, "", ""); }
        Command(Type type, String argument, String provider, String category) {
            this.type = type;
            this.argument = argument == null ? "" : argument.trim();
            this.provider = provider == null ? "" : provider.trim();
            this.category = category == null ? "" : category;
        }
    }

    public Command parse(String transcript) {
        String text = normalize(transcript);
        if (text.isEmpty()) return new Command(Type.UNKNOWN, "");

        if (text.equals("hey cu")) return new Command(Type.CANCEL, "");
        if (text.startsWith("hey cu ")) text = text.substring(7).trim();
        if (text.startsWith("kiki ")) text = text.substring(5).trim();
        if (text.startsWith("car assistant ")) text = text.substring(14).trim();

        if (matches(text, "huy", "huy lenh", "dung lai", "bo qua", "thoi", "cancel", "dung")) {
            return new Command(Type.CANCEL, "");
        }
        if (matches(text, "mo danh sach ung dung", "hien danh sach ung dung",
                "danh sach ung dung", "mo app chon", "app chon", "hien app")) {
            return new Command(Type.SHOW_APPS, "");
        }
        if (matches(text, "ve man hinh chinh", "ve trang chu",
                "tro ve man hinh chinh", "ve home", "home", "trang chu")) {
            return new Command(Type.HOME, "");
        }

        String value = firstPrefix(text,
                "dan duong den ", "dan duong toi ", "chi duong den ",
                "chi duong toi ", "chi duong ", "dieu huong den ", "dieu huong toi ");
        if (value != null) return navigationCommand(value);

        // "Đến cây xăng gần nhất bằng Navitel" is also a destination command.
        value = stripPrefix(text, "den ");
        if (value != null && !value.isEmpty()) return navigationCommand(value);

        value = stripPrefix(text, "tim quanh day ");
        if (value != null) {
            String place = nearbyCategory(value);
            if (place != null) return new Command(Type.SEARCH_NEARBY, place, "", "navigation");
        }
        value = stripPrefix(text, "tim gan day ");
        if (value != null) {
            String place = nearbyCategory(value);
            if (place != null) return new Command(Type.SEARCH_NEARBY, place, "", "navigation");
        }
        value = stripPrefix(text, "tim ");
        if (value != null) {
            String place = nearbyCategory(value);
            if (place != null) return new Command(Type.SEARCH_NEARBY, place, "", "navigation");
        }

        value = firstPrefix(text, "mo bai hat ", "phat bai hat ", "bat bai hat ", "tim bai hat ");
        if (value != null) return mediaCommand(Type.PLAY_MUSIC, value, "music");
        value = firstPrefix(text, "mo nhac ", "phat nhac ", "bat nhac ", "tim nhac ");
        if (value != null) return mediaCommand(Type.PLAY_MUSIC, value, "music");

        value = firstPrefix(text, "mo video nhac ", "mo video bai hat ");
        if (value != null) return mediaCommand(Type.PLAY_VIDEO, value, "video");
        value = firstPrefix(text, "mo video ", "xem video ", "mo youtube ", "tim video ");
        if (value != null) return mediaCommand(Type.PLAY_VIDEO, value, "video");

        value = firstPrefix(text, "mo kenh ", "chuyen kenh ", "xem kenh ");
        if (value != null) return mediaCommand(Type.OPEN_TV, value, "tv");
        if (matches(text, "mo tv", "mo truyen hinh", "bat tv", "mo ung dung tv")) {
            return new Command(Type.OPEN_TV, "", "", "tv");
        }
        if (matches(text, "mo nhac", "bat nhac", "phat nhac")) {
            return new Command(Type.PLAY_MUSIC, "", "", "music");
        }
        if (matches(text, "mo video", "xem video")) {
            return new Command(Type.PLAY_VIDEO, "", "", "video");
        }

        if (text.startsWith("mo ")) {
            String app = text.substring(3).trim();
            if (!app.isEmpty()) return new Command(Type.OPEN_APP, app);
        }
        return new Command(Type.UNKNOWN, text);
    }

    private static Command navigationCommand(String value) {
        String[] pair = splitFirst(value, " bang ", " tren ");
        if (pair[0].isEmpty()) return new Command(Type.UNKNOWN, value);
        return new Command(Type.NAVIGATE, pair[0], pair[1], "navigation");
    }

    private static Command mediaCommand(Type type, String value, String category) {
        String clean = value == null ? "" : value.trim();
        String[] pair = splitFirst(clean, " tren ", " bang ");
        return new Command(type, pair[0], pair[1], category);
    }

    private static String nearbyCategory(String value) {
        String v = value.trim();
        String[] suffixes = {"o gan day", "gan day", "gan toi", "gan nhat", "gan nhat day", "xung quanh day"};
        for (String suffix : suffixes) {
            if (v.endsWith(" " + suffix)) v = v.substring(0, v.length() - suffix.length()).trim();
            else if (v.equals(suffix)) v = "";
        }
        if (matches(v, "nha hang", "quan an", "nha hang gan nhat", "quan an gan nhat")) return "nha hang";
        if (matches(v, "quan cafe", "quan ca phe", "cafe", "ca phe", "quan coffee")) return "quan cafe";
        if (matches(v, "tiem sua xe", "gara sua xe", "sua xe", "garage sua xe")) return "tiem sua xe";
        if (matches(v, "cay xang", "tram xang", "cay xang gan nhat")) return "cay xang";
        if (matches(v, "benh vien", "atm", "bai do xe", "khach san", "nha thuoc")) return v;
        return null;
    }

    private static String firstPrefix(String value, String... prefixes) {
        for (String prefix : prefixes) {
            String result = stripPrefix(value, prefix);
            if (result != null) return result;
        }
        return null;
    }

    private static String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()).trim() : null;
    }

    private static String[] splitFirst(String value, String... delimiters) {
        int best = -1;
        String delimiter = "";
        for (String candidate : delimiters) {
            int index = value.indexOf(candidate);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
                delimiter = candidate;
            }
        }
        if (best < 0) return new String[]{value.trim(), ""};
        return new String[]{value.substring(0, best).trim(),
                value.substring(best + delimiter.length()).trim()};
    }

    private static boolean matches(String text, String... choices) {
        for (String choice : choices) if (text.equals(choice)) return true;
        return false;
    }

    /** Lowercase, remove Vietnamese diacritics and normalize whitespace for matching. */
    public static String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT).replace('đ', 'd');
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ").trim();
    }
}
