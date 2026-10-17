package com.carassistant.v10.voice;

/** Routes parsed voice intents to the existing CarAssistant projection UI. */
public final class VoiceCommandRouter {
    public interface Host {
        /** Opens a matching installed app and returns its visible label, or null when not found. */
        String openApp(String spokenName);
        /** Opens a category's explicit app or configured default. */
        String openVoiceTask(String category, String query, String explicitProvider);
        void showAppPicker();
        void goHome();
    }

    public static final class Result {
        public final boolean understood;
        public final String response;
        public final boolean cancelled;

        Result(boolean understood, String response, boolean cancelled) {
            this.understood = understood;
            this.response = response;
            this.cancelled = cancelled;
        }
    }

    private final VoiceCommandParser parser = new VoiceCommandParser();
    private final Host host;

    public VoiceCommandRouter(Host host) {
        if (host == null) throw new IllegalArgumentException("host must not be null");
        this.host = host;
    }

    public Result route(String transcript) {
        VoiceCommandParser.Command command = parser.parse(transcript);
        switch (command.type) {
            case OPEN_APP: {
                String opened = host.openApp(command.argument);
                if (opened == null) return new Result(true,
                        "Tôi chưa tìm thấy ứng dụng " + command.argument + ".", false);
                return new Result(true, "Đang mở " + opened + ".", false);
            }
            case NAVIGATE:
                return openCategory(command, "navigation", "Đang mở bản đồ");
            case SEARCH_NEARBY:
                return openCategory(command, "nearby", "Đang mở bản đồ để tìm");
            case PLAY_MUSIC:
                return openCategory(command, "music", "Đang mở ứng dụng nhạc");
            case PLAY_VIDEO:
                return openCategory(command, "video", "Đang mở ứng dụng video");
            case OPEN_TV:
                return openCategory(command, "tv", "Đang mở ứng dụng TV");
            case SHOW_APPS:
                host.showAppPicker();
                return new Result(true, "Đây là danh sách ứng dụng.", false);
            case HOME:
                host.goHome();
                return new Result(true, "Đã về màn hình chính.", false);
            case CANCEL:
                return new Result(true, "Đã hủy lệnh.", true);
            case UNKNOWN:
            default:
                return new Result(false,
                        "Tôi chưa hiểu lệnh đó. Bạn có thể nói dẫn đường đến một địa điểm, tìm địa điểm gần đây, mở nhạc, mở video, mở kênh TV hoặc mở một ứng dụng.",
                        false);
        }
    }

    private Result openCategory(VoiceCommandParser.Command command, String category, String prefix) {
        String opened = host.openVoiceTask(category, command.argument, command.provider);
        if (opened == null) {
            String categoryName = ("navigation".equals(category) || "nearby".equals(category)) ? "bản đồ"
                    : category.equals("music") ? "ứng dụng nhạc"
                    : category.equals("video") ? "ứng dụng video" : "ứng dụng TV";
            return new Result(true, "Chưa chọn được " + categoryName
                    + ". Hãy chọn ứng dụng mặc định trong phần cài đặt trợ lý.", false);
        }
        if ("music".equals(category) || "video".equals(category)) {
            if (command.argument.isEmpty()) {
                return new Result(true, "Đã gửi yêu cầu mở " + opened
                        + " ở luồng nền. Màn hình bản đồ hiện tại vẫn được giữ nguyên.", false);
            }
            String mediaType = "music".equals(category) ? "nhạc" : "video";
            return new Result(true, "Đã gửi yêu cầu tìm kiếm " + mediaType + " nền cho "
                    + command.argument + " bằng " + opened
                    + ". Màn hình bản đồ vẫn được giữ nguyên; việc tự phát phụ thuộc khả năng của ứng dụng.", false);
        }
        if ("tv".equals(category)) {
            if (command.argument.isEmpty()) {
                return new Result(true, "Đang mở ứng dụng TV " + opened + ".", false);
            }
            return new Result(true, "Đã mở " + opened + " và gửi yêu cầu tìm kênh "
                    + command.argument + ". Việc tự chuyển kênh phụ thuộc ứng dụng TV.", false);
        }
        String detail = command.argument.isEmpty() ? "" : " cho nội dung " + command.argument;
        if (!command.provider.isEmpty()) detail += " bằng " + command.provider;
        return new Result(true, prefix + " " + opened + detail
                + ". Nếu ứng dụng chưa tự nhận truy vấn, hãy xác nhận trên màn hình.", false);
    }
}
