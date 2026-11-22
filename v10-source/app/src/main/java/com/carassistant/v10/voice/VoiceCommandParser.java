package com.carassistant.v10.voice;

import java.text.Normalizer;
import java.util.Locale;

/** Deterministic Vietnamese voice command parser for CarAssistant. */
public final class VoiceCommandParser {
    public enum Type {
        OPEN_APP, SHOW_APPS, HOME, CANCEL, EXPAND_PANE,
        NAVIGATE, SEARCH_NEARBY, PLAY_MUSIC, PLAY_VIDEO, OPEN_TV,
        UNKNOWN
    }

    public enum TargetScreen { AUTO, LEFT, RIGHT, FULLSCREEN }

    public static final class Command {
        public final Type type;
        public final TargetScreen targetScreen;
        /** Destination, search query, channel, or app name. */
        public final String argument;
        /** Explicit provider/app spoken after "bằng" or "trên". */
        public final String provider;
        /** navigation, music, video, or tv; empty for general commands. */
        public final String category;

        Command(Type type, String argument) { this(type, argument, "", "", TargetScreen.AUTO); }
        Command(Type type, String argument, String provider, String category) {
            this(type, argument, provider, category, TargetScreen.AUTO);
        }
        Command(Type type, String argument, String provider, String category, TargetScreen targetScreen) {
            this.type = type;
            this.targetScreen = targetScreen == null ? TargetScreen.AUTO : targetScreen;
            this.argument = argument == null ? "" : argument.trim();
            this.provider = provider == null ? "" : provider.trim();
            this.category = category == null ? "" : category;
        }
    }

    public Command parse(String transcript) {
        String text = normalize(transcript);
        if (text.isEmpty()) return new Command(Type.UNKNOWN, "");
        TargetScreen target = TargetScreen.AUTO;
        boolean fullRequest = containsAny(text, "full", "toan man hinh", "toan man", "phong to");
        boolean leftRequested = containsAny(text, "ben trai", "man hinh trai", "man hinh 1", "vung trai");
        boolean rightRequested = containsAny(text, "ben phai", "man hinh phai", "man hinh 1 1", "vung phai");
        if (fullRequest && leftRequested) return command(TargetScreen.LEFT, Type.EXPAND_PANE, "");
        if (fullRequest && rightRequested) return command(TargetScreen.RIGHT, Type.EXPAND_PANE, "");
        // "Mở full <app>" and "full <app>" launch directly on Display 2.
        if (text.startsWith("mo full ")) {
            target = TargetScreen.FULLSCREEN;
            text = "mo " + text.substring("mo full ".length()).trim();
        } else if (text.startsWith("full ") && !text.startsWith("full man hinh")) {
            target = TargetScreen.FULLSCREEN;
            text = "mo " + text.substring("full ".length()).trim();
        }
        // Extract screen intent as a separate modifier so the command body can be
        // phrased naturally ("đưa ... qua bên trái", "chiếu ... toàn màn hình").
        if (target != TargetScreen.FULLSCREEN && containsAny(text, "full man hinh", "toan man hinh", "toan man", "full man", "phong to toan man", "phong to man hinh")) {
            target = TargetScreen.FULLSCREEN;
            text = removeAny(text, "full man hinh", "toan man hinh", "phong to toan man", "phong to man hinh", "toan man", "full man");
        } else if (containsAny(text, "ben trai", "o ben trai", "sang ben trai", "qua ben trai", "dua sang ben trai", "sang man hinh trai", "qua man hinh trai", "o man hinh trai", "man hinh trai", "vung trai", "o trai", "sang trai", "qua trai")) {
            target = TargetScreen.LEFT;
            text = removeAny(text, "dua sang ben trai", "sang ben trai", "qua ben trai", "o ben trai", "sang man hinh trai", "qua man hinh trai", "o man hinh trai", "man hinh ben trai", "man hinh trai", "vung ben trai", "vung trai", "ben trai", "o trai", "sang trai", "qua trai");
        } else if (containsAny(text, "ben phai", "o ben phai", "sang ben phai", "qua ben phai", "dua sang ben phai", "sang man hinh phai", "qua man hinh phai", "o man hinh phai", "man hinh phai", "vung phai", "o phai", "sang phai", "qua phai")) {
            target = TargetScreen.RIGHT;
            text = removeAny(text, "dua sang ben phai", "sang ben phai", "qua ben phai", "o ben phai", "sang man hinh phai", "qua man hinh phai", "o man hinh phai", "man hinh ben phai", "man hinh phai", "vung ben phai", "vung phai", "ben phai", "o phai", "sang phai", "qua phai");
        }
        text = text.replaceAll("\\s+", " ").trim();

        if (text.equals("hey cu")) return command(target, Type.CANCEL, "");
        if (text.startsWith("hey cu ")) text = text.substring(7).trim();
        if (text.startsWith("kiki ")) text = text.substring(5).trim();
        if (text.startsWith("car assistant ")) text = text.substring(14).trim();

        if (matches(text, "huy", "huy lenh", "huy bo", "dung lai", "dung nghe", "bo qua", "thoi", "cancel", "dung")) {
            return command(target, Type.CANCEL, "");
        }
        if (matches(text, "mo danh sach ung dung", "hien danh sach ung dung",
                "danh sach ung dung", "mo app chon", "app chon", "hien app",
                "liet ke ung dung", "cho xem cac ung dung", "chon ung dung")) {
            return command(target, Type.SHOW_APPS, "");
        }
        if (matches(text, "ve man hinh chinh", "ve trang chu",
                "tro ve man hinh chinh", "tro ve home", "quay ve home",
                "quay ve trang chu", "ve giao dien chinh", "quay ve giao dien chinh", "tro ve giao dien chinh", "home", "trang chu")) {
            return command(target, Type.HOME, "");
        }

        String value = firstPrefix(text,
                "dan duong den ", "dan duong toi ", "dan toi ", "dan den ",
                "chi duong den ", "chi duong toi ", "chi duong ", "tim duong den ",
                "tim duong toi ", "dieu huong den ", "dieu huong toi ",
                "dua toi den ", "dua toi toi ", "di den ", "di toi ", "lai xe den ");
        if (value != null) return navigationCommand(value, target);

        // "Đến cây xăng gần nhất bằng Navitel" is also a destination command.
        value = firstPrefix(text, "den ", "toi ", "di den ", "di toi ");
        if (value != null && !value.isEmpty()) return navigationCommand(value, target);

        value = firstPrefix(text, "tim quanh day ", "tim xung quanh ", "tim o gan day ", "tim gan toi ");
        if (value != null) {
            String place = nearbyCategory(value);
            if (place != null) return command(target, Type.SEARCH_NEARBY, place, "", "navigation");
        }
        value = firstPrefix(text, "tim gan day ", "tim dia diem gan day ", "tim dia diem quanh day ");
        if (value != null) {
            String place = nearbyCategory(value);
            if (place != null) return command(target, Type.SEARCH_NEARBY, place, "", "navigation");
        }
        value = stripPrefix(text, "tim ");
        if (value != null) {
            String place = nearbyCategory(value);
            if (place != null) return command(target, Type.SEARCH_NEARBY, place, "", "navigation");
        }

        value = firstPrefix(text, "mo bai hat ", "phat bai hat ", "bat bai hat ", "tim bai hat ", "cho phat bai hat ",
                "mo ca khuc ", "phat ca khuc ", "tim ca khuc ", "mo ban nhac ", "phat ban nhac ");
        if (value != null) return mediaCommand(Type.PLAY_MUSIC, value, "music", target);
        value = firstPrefix(text, "mo nhac ", "phat nhac ", "bat nhac ", "tim nhac ",
                "nghe nhac ", "cho nghe ", "mo playlist ", "phat playlist ");
        if (value != null) return mediaCommand(Type.PLAY_MUSIC, value, "music", target);

        value = firstPrefix(text, "mo video nhac ", "phat video nhac ", "tim video nhac ",
                "xem video nhac ", "mo mv ", "xem mv ");
        if (value != null) {
            // Keep "nhạc trẻ/bolero/rap/remix..." as the actual search phrase,
            // while treating "nhạc <artist/song>" as a command prefix.
            if (startsWithAny(value, "tre ", "bolero", "rap ", "remix", "lofi", "tru tinh", "tinh ca")) {
                value = "nhac " + value;
            }
            return mediaCommand(Type.PLAY_VIDEO, value, "video", target);
        }
        value = firstPrefix(text, "mo video bai hat ", "phat video bai hat ", "tim video bai hat ",
                "xem video bai hat ", "mo clip ", "xem clip ");
        if (value != null) return mediaCommand(Type.PLAY_VIDEO, value, "video", target);
        value = firstPrefix(text, "mo video ", "phat video ", "xem video ", "tim video ",
                "bat video ", "mo youtube ", "xem youtube ");
        if (value != null) return mediaCommand(Type.PLAY_VIDEO, value, "video", target);

        value = firstPrefix(text, "mo phim ", "phat phim ", "xem phim ", "tim phim ",
                "mo bo phim ", "phat bo phim ", "tim bo phim ");
        if (value != null) return mediaCommand(Type.PLAY_VIDEO, value, "video", target);

        value = firstPrefix(text, "mo kenh ", "chuyen kenh ", "xem kenh ", "bat kenh ", "doi kenh ");
        if (value != null) return mediaCommand(Type.OPEN_TV, value, "tv", target);
        if (matches(text, "mo tv", "mo truyen hinh", "bat tv", "mo ung dung tv", "xem tv", "bat truyen hinh")) {
            return command(target, Type.OPEN_TV, "", "", "tv");
        }
        if (matches(text, "mo nhac", "bat nhac", "phat nhac")) {
            return command(target, Type.PLAY_MUSIC, "", "", "music");
        }
        if (matches(text, "mo video", "phat video", "xem video", "mo phim", "phat phim", "xem phim", "mo youtube")) {
            return command(target, Type.PLAY_VIDEO, "", "", "video");
        }

        // Generic media wording: "Mở <tên nội dung> trên YouTube" and
        // "Mở <tên bài> trên YouTube Music/Zing MP3" need to be treated as
        // search-and-play requests, not as requests to launch an app by name.
        value = firstPrefix(text, "mo ", "phat ", "bat ", "xem ", "tim ", "cho phat ");
        if (value != null) {
            String[] mediaPair = splitFirst(value, " tren ", " bang ");
            String provider = normalize(mediaPair[1]);
            if (!mediaPair[1].isEmpty() && !mediaPair[0].isEmpty()) {
                if (provider.contains("youtube music") || provider.contains("zing mp3")
                        || provider.contains("spotify") || provider.contains("nhaccuatui")
                        || provider.contains("nhac cua tui")) {
                    return command(target, Type.PLAY_MUSIC, mediaPair[0], mediaPair[1], "music");
                }
                if (provider.contains("youtube") || provider.contains("revanced")
                        || provider.contains("morphe")) {
                    return command(target, Type.PLAY_VIDEO, mediaPair[0], mediaPair[1], "video");
                }
            }
        }

        // App-launch synonyms. Keep this after media parsing so "mở bài hát ..."
        // is not mistaken for an installed application.
        value = firstPrefix(text, "mo ung dung ", "mo app ", "khoi dong ",
                "chay ung dung ", "chay app ", "mo lai ", "chuyen sang ",
                "chuyen qua ", "doi sang ", "dua len man hinh ", "chieu ", "phong to ");
        if (value != null && !value.isEmpty()) {
            if (value.startsWith("ung dung ")) value = value.substring(9).trim();
            else if (value.startsWith("app ")) value = value.substring(4).trim();
            if (!value.isEmpty()) return command(target, Type.OPEN_APP, value);
        }
        if (text.startsWith("mo ")) {
            String app = text.substring(3).trim();
            if (app.startsWith("ung dung ")) app = app.substring(9).trim();
            else if (app.startsWith("app ")) app = app.substring(4).trim();
            if (!app.isEmpty()) return command(target, Type.OPEN_APP, app);
        }
        return command(target, Type.UNKNOWN, text);
    }

    private static Command command(TargetScreen target, Type type, String argument) {
        return new Command(type, argument, "", "", target);
    }

    private static Command command(TargetScreen target, Type type, String argument,
                                   String provider, String category) {
        return new Command(type, argument, provider, category, target);
    }

    private static boolean containsAny(String value, String... choices) {
        for (String choice : choices) if (value.contains(choice)) return true;
        return false;
    }

    private static String removeAny(String value, String... choices) {
        String result = value;
        for (String choice : choices) result = result.replace(choice, " ");
        return result;
    }

    private static Command navigationCommand(String value, TargetScreen target) {
        String[] pair = splitFirst(value, " bang ", " tren ");
        if (pair[0].isEmpty()) return command(target, Type.UNKNOWN, value);
        return command(target, Type.NAVIGATE, pair[0], pair[1], "navigation");
    }

    private static Command mediaCommand(Type type, String value, String category, TargetScreen target) {
        String clean = value == null ? "" : value.trim();
        String[] pair = splitFirst(clean, " tren ", " bang ");
        return command(target, type, pair[0], pair[1], category);
    }

    private static String nearbyCategory(String value) {
        String v = value.trim();
        String[] suffixes = {"o gan day", "gan day", "gan toi", "gan nhat", "gan nhat day", "xung quanh day"};
        for (String suffix : suffixes) {
            if (v.endsWith(" " + suffix)) v = v.substring(0, v.length() - suffix.length()).trim();
            else if (v.equals(suffix)) v = "";
        }
        if (matches(v, "nha hang", "quan an", "do an", "quan nhau", "nha hang gan nhat", "quan an gan nhat")) return "nha hang";
        if (matches(v, "quan cafe", "quan ca phe", "cafe", "ca phe", "quan coffee", "coffee", "tra sua")) return "quan cafe";
        if (matches(v, "tiem sua xe", "gara sua xe", "sua xe", "garage sua xe", "sua oto", "sua xe hoi")) return "tiem sua xe";
        if (matches(v, "cay xang", "tram xang", "do xang", "tram do xang", "cay xang gan nhat")) return "cay xang";
        if (matches(v, "benh vien", "phong kham", "atm", "ngan hang", "bai do xe", "cho do xe",
                "khach san", "nha nghi", "nha thuoc", "buu dien", "sieu thi", "cho", "truong hoc")) return v;
        return null;
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) if (value.startsWith(prefix)) return true;
        return false;
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
