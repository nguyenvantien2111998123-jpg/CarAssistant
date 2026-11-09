package com.carassistant.v10;

import android.graphics.Bitmap;

import com.carassistant.v10.engine.warning.TrafficWarningEngine;

/**
 * Compatibility facade retained for existing MediaSessionBridge and V10 UI call sites.
 * State and artwork composition live in the standalone TrafficWarningEngine.
 */
public final class WynWarningArtwork {
    private WynWarningArtwork() { }

    public static synchronized boolean setSpeedSignBitmap(Bitmap source) {
        return TrafficWarningEngine.getInstance().updateSpeedSign(source);
    }

    public static synchronized boolean setTemporaryAlertBitmap(Bitmap source) {
        return TrafficWarningEngine.getInstance().updateTemporaryAlert(source);
    }

    public static synchronized boolean clearTemporaryAlert() {
        return TrafficWarningEngine.getInstance().clearTemporaryAlert();
    }

    public static synchronized boolean clearAllWarnings() {
        return TrafficWarningEngine.getInstance().clearAllWarnings();
    }

    public static boolean hasWarnings() {
        return TrafficWarningEngine.getInstance().hasWarnings();
    }

    public static Bitmap composeMediaArtwork(Bitmap media) {
        return TrafficWarningEngine.getInstance().composeMediaArtwork(media);
    }

    public static Bitmap createWarningOnlyArtwork(int size) {
        return TrafficWarningEngine.getInstance().composeWarningOnlyArtwork(size);
    }

    /** Generic multi-source publication facade used by source adapters. */
    public static synchronized boolean publishWarning(
            com.carassistant.v10.engine.warning.TrafficWarningSource source,
            String carStreamWarningId, Bitmap bitmap) {
        return TrafficWarningEngine.getInstance()
                .publishWarning(source, carStreamWarningId, bitmap);
    }

    public static synchronized boolean clearSource(
            com.carassistant.v10.engine.warning.TrafficWarningSource source) {
        return TrafficWarningEngine.getInstance().clearSource(source);
    }

    public static long getGeneration() {
        return TrafficWarningEngine.getInstance().getSnapshot().generation;
    }
}
