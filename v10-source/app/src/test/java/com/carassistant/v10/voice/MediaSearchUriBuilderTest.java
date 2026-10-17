package com.carassistant.v10.voice;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class MediaSearchUriBuilderTest {
    @Test
    public void routesOfficialYoutubeMusicToMusicSearch() {
        assertEquals("https://music.youtube.com/search?q=Noi%20nay%20co%20anh",
                MediaSearchUriBuilder.build(
                        "com.google.android.apps.youtube.music", "Noi nay co anh", "music"));
    }

    @Test
    public void routesMorpheYoutubeMusicToMusicSearch() {
        assertEquals("https://music.youtube.com/search?q=Noi%20nay%20co%20anh",
                MediaSearchUriBuilder.build(
                        "app.morphe.android.apps.youtube.music", "Noi nay co anh", "music"));
    }

    @Test
    public void routesYoutubeMorpheToVideoSearch() {
        assertEquals("https://www.youtube.com/results?search_query=meo%20hai",
                MediaSearchUriBuilder.build("app.morphe.android.youtube", "meo hai", "video"));
    }

    @Test
    public void routesYoutubeRevancedToVideoSearch() {
        assertEquals("https://www.youtube.com/results?search_query=huong%20dan%20sua%20xe",
                MediaSearchUriBuilder.build(
                        "app.revanced.android.youtube", "huong dan sua xe", "video"));
    }

    @Test
    public void routesZingMp3ToSearch() {
        assertEquals("https://zingmp3.vn/tim-kiem/tat-ca?q=son%20tung",
                MediaSearchUriBuilder.build("com.zing.mp3", "son tung", "music"));
    }

    @Test
    public void safelyEncodesReservedQueryCharacters() {
        String uri = MediaSearchUriBuilder.build(
                "com.google.android.youtube", "rock & roll + live", "video");
        assertTrue(uri.endsWith("rock%20%26%20roll%20%2B%20live"));
    }

    @Test
    public void unknownProviderDoesNotInventSearchUri() {
        assertNull(MediaSearchUriBuilder.build("com.example.player", "song", "music"));
    }

    @Test
    public void rejectsEmptySearchQuery() {
        assertNull(MediaSearchUriBuilder.build("com.google.android.youtube", "  ", "video"));
    }
}
