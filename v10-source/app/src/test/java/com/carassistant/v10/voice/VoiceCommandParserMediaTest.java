package com.carassistant.v10.voice;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class VoiceCommandParserMediaTest {
    @Test
    public void parsesMusicSearchWithExplicitYoutubeProvider() {
        VoiceCommandParser.Command command =
                new VoiceCommandParser().parse("Mở nhạc Sơn Tùng trên YouTube");

        assertEquals(VoiceCommandParser.Type.PLAY_MUSIC, command.type);
        assertEquals("son tung", command.argument);
        assertEquals("youtube", command.provider);
        assertEquals("music", command.category);
    }

    @Test
    public void matchesSpecificMusicVideoPhraseBeforeGenericVideoPhrase() {
        VoiceCommandParser.Command command =
                new VoiceCommandParser().parse("Mở video nhạc Sơn Tùng trên YouTube");

        assertEquals(VoiceCommandParser.Type.PLAY_VIDEO, command.type);
        assertEquals("nhac son tung", command.argument);
        assertEquals("youtube", command.provider);
        assertEquals("video", command.category);
    }
}
