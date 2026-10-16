package com.carassistant.v10.voice;

import java.text.Normalizer;
import java.util.Locale;

/** Small, deterministic Vietnamese command parser for push-to-talk V1. */
public final class VoiceCommandParser {
    public enum Type {
        OPEN_APP,
        SHOW_APPS,
        HOME,
        CANCEL,
        UNKNOWN
    }

    public static final class Command {
        public final Type type;
        public final String argument;

        Command(Type type, String argument) {
            this.type = type;
            this.argument = argument == null ? "" : argument.trim();
        }
    }

    public Command parse(String transcript) {
        String text = normalize(transcript);
        if (text.isEmpty()) return new Command(Type.UNKNOWN, "");

        // Accept an optional assistant name at the beginning without requiring wake-word mode.
        if (text.startsWith("kiki ")) text = text.substring(5).trim();
        if (text.startsWith("car assistant ")) text = text.substring(14).trim();

        if (matches(text, "huy", "huy lenh", "dung lai", "bo qua", "thoi")) {
            return new Command(Type.CANCEL, "");
        }

        if (matches(text,
                "mo danh sach ung dung",
                "hien danh sach ung dung",
                "danh sach ung dung",
                "mo app chon",
                "app chon",
                "hien app")) {
            return new Command(Type.SHOW_APPS, "");
        }

        if (matches(text,
                "ve man hinh chinh",
                "ve trang chu",
                "tro ve man hinh chinh",
                "ve home",
                "home",
                "trang chu")) {
            return new Command(Type.HOME, "");
        }

        if (text.startsWith("mo ")) {
            String app = text.substring(3).trim();
            if (!app.isEmpty()) return new Command(Type.OPEN_APP, app);
        }

        return new Command(Type.UNKNOWN, text);
    }

    private static boolean matches(String text, String... choices) {
        for (String choice : choices) {
            if (text.equals(choice)) return true;
        }
        return false;
    }

    /** Lowercase, remove Vietnamese diacritics and normalize whitespace for matching. */
    public static String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT).replace('đ', 'd');
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
