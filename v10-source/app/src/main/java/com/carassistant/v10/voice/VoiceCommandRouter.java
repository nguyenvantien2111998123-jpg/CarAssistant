package com.carassistant.v10.voice;

/** Routes parsed voice intents to existing CarAssistant UI actions. */
public final class VoiceCommandRouter {
    public interface Host {
        /** Opens a matching installed app and returns its visible label, or null when not found. */
        String openApp(String spokenName);
        void showApps();
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
                if (opened == null) {
                    return new Result(true,
                            "Tôi chưa tìm thấy ứng dụng " + command.argument + ".",
                            false);
                }
                return new Result(true, "Đang mở " + opened + ".", false);
            }
            case SHOW_APPS:
                host.showApps();
                return new Result(true, "Đây là danh sách ứng dụng.", false);
            case HOME:
                host.goHome();
                return new Result(true, "Đã về màn hình chính.", false);
            case CANCEL:
                return new Result(true, "Đã hủy lệnh.", true);
            case UNKNOWN:
            default:
                return new Result(false,
                        "Tôi chưa hiểu lệnh đó. Bạn hãy thử nói mở một ứng dụng, hiện danh sách ứng dụng hoặc về màn hình chính.",
                        false);
        }
    }
}
