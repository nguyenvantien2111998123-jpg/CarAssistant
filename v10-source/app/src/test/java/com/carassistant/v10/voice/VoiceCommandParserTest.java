package com.carassistant.v10.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class VoiceCommandParserTest {
    private final VoiceCommandParser parser = new VoiceCommandParser();

    @Test
    public void parsesVietnameseOpenApp() {
        VoiceCommandParser.Command command = parser.parse("Mở Google Maps");
        assertEquals(VoiceCommandParser.Type.OPEN_APP, command.type);
        assertEquals("google maps", command.argument);
    }

    @Test
    public void parsesAppPickerWithOrWithoutDiacritics() {
        assertEquals(VoiceCommandParser.Type.SHOW_APPS,
                parser.parse("Hiện danh sách ứng dụng").type);
        assertEquals(VoiceCommandParser.Type.SHOW_APPS,
                parser.parse("mo app chon").type);
    }

    @Test
    public void parsesHomeAndCancel() {
        assertEquals(VoiceCommandParser.Type.HOME,
                parser.parse("Về màn hình chính").type);
        assertEquals(VoiceCommandParser.Type.CANCEL,
                parser.parse("Hủy lệnh").type);
    }

    @Test
    public void unknownSpeechDoesNotBecomeAnAction() {
        assertEquals(VoiceCommandParser.Type.UNKNOWN,
                parser.parse("hôm nay trời đẹp").type);
    }
}
