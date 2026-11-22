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

    @Test
    public void understandsAppLaunchSynonymsAndExplicitLeftPane() {
        VoiceCommandParser.Command command = parser.parse("Chuyển sang ứng dụng YouTube qua bên trái");
        assertEquals(VoiceCommandParser.Type.OPEN_APP, command.type);
        assertEquals("youtube", command.argument);
        assertEquals(VoiceCommandParser.TargetScreen.LEFT, command.targetScreen);
    }

    @Test
    public void understandsOpenAppAndRightPaneWording() {
        VoiceCommandParser.Command command = parser.parse("Khởi động app Vietmap ở bên phải");
        assertEquals(VoiceCommandParser.Type.OPEN_APP, command.type);
        assertEquals("vietmap", command.argument);
        assertEquals(VoiceCommandParser.TargetScreen.RIGHT, command.targetScreen);
    }

    @Test
    public void understandsFullscreenProjectionSynonyms() {
        VoiceCommandParser.Command command = parser.parse("Phóng to YouTube toàn màn hình");
        assertEquals(VoiceCommandParser.Type.OPEN_APP, command.type);
        assertEquals("youtube", command.argument);
        assertEquals(VoiceCommandParser.TargetScreen.FULLSCREEN, command.targetScreen);
    }

    @Test
    public void expandsCurrentLeftPaneOnFullscreenLeftCommand() {
        VoiceCommandParser.Command command = parser.parse("Full bên trái");
        assertEquals(VoiceCommandParser.Type.EXPAND_PANE, command.type);
        assertEquals(VoiceCommandParser.TargetScreen.LEFT, command.targetScreen);
    }

    @Test
    public void expandsCurrentRightPaneOnFullscreenRightCommand() {
        VoiceCommandParser.Command command = parser.parse("Toàn màn hình bên phải");
        assertEquals(VoiceCommandParser.Type.EXPAND_PANE, command.type);
        assertEquals(VoiceCommandParser.TargetScreen.RIGHT, command.targetScreen);
    }

    @Test
    public void opensNamedAppDirectlyFullscreen() {
        VoiceCommandParser.Command command = parser.parse("Mở full app YouTube");
        assertEquals(VoiceCommandParser.Type.OPEN_APP, command.type);
        assertEquals("youtube", command.argument);
        assertEquals(VoiceCommandParser.TargetScreen.FULLSCREEN, command.targetScreen);
    }

    @Test
    public void parsesEnglishOpenAppAndPaneTarget() {
        VoiceCommandParser.Command command = parser.parse("Open YouTube on the left");
        assertEquals(VoiceCommandParser.Type.OPEN_APP, command.type);
        assertEquals("youtube", command.argument);
        assertEquals(VoiceCommandParser.TargetScreen.LEFT, command.targetScreen);
    }

    @Test
    public void parsesEnglishFullscreenAppCommand() {
        VoiceCommandParser.Command command = parser.parse("Open full screen YouTube");
        assertEquals(VoiceCommandParser.Type.OPEN_APP, command.type);
        assertEquals("youtube", command.argument);
        assertEquals(VoiceCommandParser.TargetScreen.FULLSCREEN, command.targetScreen);
    }

    @Test
    public void parsesEnglishNavigationCommand() {
        VoiceCommandParser.Command command = parser.parse("Navigate to the airport using Google Maps");
        assertEquals(VoiceCommandParser.Type.NAVIGATE, command.type);
        assertEquals("the airport", command.argument);
        assertEquals("google maps", command.provider);
    }

    @Test
    public void parsesEnglishFullscreenPaneCommand() {
        VoiceCommandParser.Command command = parser.parse("Full screen right");
        assertEquals(VoiceCommandParser.Type.EXPAND_PANE, command.type);
        assertEquals(VoiceCommandParser.TargetScreen.RIGHT, command.targetScreen);
    }

    @Test
    public void fuzzyMatcherAcceptsCommonVietmapLiveRecognitionTypo() {
        assertEquals(true, VoiceCommandParser.nameSimilarityPercent(
                "vietmep lỉke", "VIETMAP LIVE") >= 82);
    }

    @Test
    public void fuzzyMatcherRejectsUnrelatedAppNames() {
        assertEquals(true, VoiceCommandParser.nameSimilarityPercent(
                "vietmap live", "youtube music") < 82);
    }

    @Test
    public void understandsNavigationSynonyms() {
        VoiceCommandParser.Command command = parser.parse("Tìm đường tới sân bay Buôn Ma Thuột bằng Vietmap");
        assertEquals(VoiceCommandParser.Type.NAVIGATE, command.type);
        assertEquals("san bay buon ma thuot", command.argument);
        assertEquals("vietmap", command.provider);
    }

    @Test
    public void understandsNearbySearchSynonyms() {
        VoiceCommandParser.Command command = parser.parse("Tìm trạm đổ xăng gần tôi");
        assertEquals(VoiceCommandParser.Type.SEARCH_NEARBY, command.type);
        assertEquals("cay xang", command.argument);
    }

    @Test
    public void understandsNaturalHomeAndAppPickerCommands() {
        assertEquals(VoiceCommandParser.Type.HOME, parser.parse("Quay về giao diện chính").type);
        assertEquals(VoiceCommandParser.Type.SHOW_APPS, parser.parse("Cho xem các ứng dụng").type);
    }

    @Test
    public void doesNotConfuseMediaSearchWithAppLaunch() {
        VoiceCommandParser.Command command = parser.parse("Cho phát bài hát Nơi này có anh trên YouTube Music");
        assertEquals(VoiceCommandParser.Type.PLAY_MUSIC, command.type);
        assertEquals("noi nay co anh", command.argument);
        assertEquals("youtube music", command.provider);
    }

}
