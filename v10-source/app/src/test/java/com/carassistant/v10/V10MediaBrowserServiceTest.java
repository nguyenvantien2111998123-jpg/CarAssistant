package com.carassistant.v10;

import org.junit.Test;
import static org.junit.Assert.*;

public class V10MediaBrowserServiceTest {
    @Test public void publishesOnlyForMatchingProjectedPackage() {
        assertTrue(V10MediaBrowserService.isTargetMedia("app.revanced.android.youtube",
                "app.revanced.android.youtube"));
        assertFalse(V10MediaBrowserService.isTargetMedia("com.zing.mp3",
                "app.revanced.android.youtube"));
        assertFalse(V10MediaBrowserService.isTargetMedia(null, "com.zing.mp3"));
        assertFalse(V10MediaBrowserService.isTargetMedia("com.zing.mp3", null));
        assertFalse(V10MediaBrowserService.isTargetMedia("", ""));
    }
}
