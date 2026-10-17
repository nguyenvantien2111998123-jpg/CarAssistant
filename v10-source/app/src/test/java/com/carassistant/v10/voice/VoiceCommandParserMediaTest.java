package com.carassistant.v10.voice;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class VoiceCommandParserMediaTest {
    private final VoiceCommandParser parser = new VoiceCommandParser();

    @Test
    public void parsesMusicSearchWithExplicitYoutubeProvider() {
        VoiceCommandParser.Command command = parser.parse("Mở nhạc Sơn Tùng trên YouTube");
        assertEquals(VoiceCommandParser.Type.PLAY_MUSIC, command.type);
        assertEquals("son tung", command.argument);
        assertEquals("youtube", command.provider);
        assertEquals("music", command.category);
    }

    @Test
    public void parsesZingMp3ArtistSearch() {
        VoiceCommandParser.Command command = parser.parse("Mở nhạc Sơn Tùng M-TP trên Zing MP3");
        assertEquals(VoiceCommandParser.Type.PLAY_MUSIC, command.type);
        assertEquals("son tung m tp", command.argument);
        assertEquals("zing mp3", command.provider);
    }

    @Test
    public void parsesVideoGenreSearchWithExplicitYoutubeProvider() {
        VoiceCommandParser.Command command = parser.parse("Mở video nhạc trẻ mới nhất trên YouTube");
        assertEquals(VoiceCommandParser.Type.PLAY_VIDEO, command.type);
        assertEquals("nhac tre moi nhat", command.argument);
        assertEquals("youtube", command.provider);
        assertEquals("video", command.category);
    }

    @Test
    public void matchesSpecificMusicVideoPhraseBeforeGenericVideoPhrase() {
        VoiceCommandParser.Command command = parser.parse("Mở video nhạc Sơn Tùng trên YouTube");
        assertEquals(VoiceCommandParser.Type.PLAY_VIDEO, command.type);
        assertEquals("son tung", command.argument);
        assertEquals("youtube", command.provider);
        assertEquals("video", command.category);
    }

    @Test
    public void parsesPlayVideoPhraseOnYoutube() {
        VoiceCommandParser.Command command = parser.parse("Phát video hướng dẫn sửa xe trên YouTube");
        assertEquals(VoiceCommandParser.Type.PLAY_VIDEO, command.type);
        assertEquals("huong dan sua xe", command.argument);
        assertEquals("youtube", command.provider);
        assertEquals("video", command.category);
    }

    @Test
    public void parsesMovieSearchOnYoutube() {
        VoiceCommandParser.Command command = parser.parse("Tìm phim Dune trên YouTube");
        assertEquals(VoiceCommandParser.Type.PLAY_VIDEO, command.type);
        assertEquals("dune", command.argument);
        assertEquals("youtube", command.provider);
        assertEquals("video", command.category);
    }

    @Test
    public void parsesOpenMoviePhraseOnYoutube() {
        VoiceCommandParser.Command command = parser.parse("Mở phim Avatar trên YouTube");
        assertEquals(VoiceCommandParser.Type.PLAY_VIDEO, command.type);
        assertEquals("avatar", command.argument);
        assertEquals("youtube", command.provider);
    }


    @Test
    public void parsesGenericOpenPhraseAsVideoSearchWhenYoutubeIsNamed() {
        VoiceCommandParser.Command command = parser.parse("Mở Sơn Tùng trên YouTube");
        assertEquals(VoiceCommandParser.Type.PLAY_VIDEO, command.type);
        assertEquals("son tung", command.argument);
        assertEquals("youtube", command.provider);
        assertEquals("video", command.category);
    }

    @Test
    public void parsesGenericOpenPhraseAsMusicSearchWhenYoutubeMusicIsNamed() {
        VoiceCommandParser.Command command = parser.parse("Mở Nơi này có anh trên YouTube Music Morphe");
        assertEquals(VoiceCommandParser.Type.PLAY_MUSIC, command.type);
        assertEquals("noi nay co anh", command.argument);
        assertEquals("youtube music morphe", command.provider);
        assertEquals("music", command.category);
    }

    @Test
    public void wakePhraseCanPrefixMediaCommand() {
        VoiceCommandParser.Command command = parser.parse("HEY CU, mở video mèo hài trên YouTube");
        assertEquals(VoiceCommandParser.Type.PLAY_VIDEO, command.type);
        assertEquals("meo hai", command.argument);
        assertEquals("youtube", command.provider);
    }

    @Test
    public void parsesNavigationDestinationAndMapProvider() {
        VoiceCommandParser.Command command = parser.parse("Dẫn đường đến chợ Bến Thành bằng Google Maps");
        assertEquals(VoiceCommandParser.Type.NAVIGATE, command.type);
        assertEquals("cho ben thanh", command.argument);
        assertEquals("google maps", command.provider);
    }

    @Test
    public void parsesAlternateNavigationWording() {
        VoiceCommandParser.Command command = parser.parse("Chỉ đường tới sân bay Đà Nẵng bằng Vietmap");
        assertEquals(VoiceCommandParser.Type.NAVIGATE, command.type);
        assertEquals("san bay da nang", command.argument);
        assertEquals("vietmap", command.provider);
    }

    @Test
    public void parsesNearbyGarageSearch() {
        VoiceCommandParser.Command command = parser.parse("Tìm tiệm sửa xe gần đây");
        assertEquals(VoiceCommandParser.Type.SEARCH_NEARBY, command.type);
        assertEquals("tiem sua xe", command.argument);
    }

    @Test
    public void parsesNearestFuelStationAsNavigationDestination() {
        VoiceCommandParser.Command command = parser.parse("Đến cây xăng gần nhất bằng Navitel");
        assertEquals(VoiceCommandParser.Type.NAVIGATE, command.type);
        assertEquals("cay xang gan nhat", command.argument);
        assertEquals("navitel", command.provider);
    }

    @Test
    public void parsesTvChannel() {
        VoiceCommandParser.Command command = parser.parse("Mở kênh VTV1");
        assertEquals(VoiceCommandParser.Type.OPEN_TV, command.type);
        assertEquals("vtv1", command.argument);
        assertEquals("tv", command.category);
    }
}
