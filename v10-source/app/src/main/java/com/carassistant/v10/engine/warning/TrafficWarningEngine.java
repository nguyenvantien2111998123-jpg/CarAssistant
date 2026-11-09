package com.carassistant.v10.engine.warning;

import android.graphics.Bitmap;

import com.carassistant.v10.engine.artwork.MediaCardArtworkComposer;

/**
 * Process-wide WYN warning state engine. WYN remains authoritative for warning
 * selection and timing; this class only mirrors its two published image channels.
 */
public final class TrafficWarningEngine {
    private static final TrafficWarningEngine INSTANCE = new TrafficWarningEngine();

    private final WarningStateRepository repository = new WarningStateRepository();

    private TrafficWarningEngine() { }

    public static TrafficWarningEngine getInstance() {
        return INSTANCE;
    }

    public boolean updateSpeedSign(Bitmap bitmap) {
        return repository.setSpeedSign(bitmap);
    }

    public boolean updateTemporaryAlert(Bitmap bitmap) {
        return repository.setTemporaryAlert(bitmap);
    }

    public boolean clearTemporaryAlert() {
        return repository.clearTemporaryAlert();
    }

    public boolean clearAllWarnings() {
        return repository.clearAll();
    }

    public WarningStateRepository.Snapshot getSnapshot() {
        return repository.snapshot();
    }

    public boolean hasWarnings() {
        return repository.snapshot().hasWarnings();
    }

    public Bitmap composeMediaArtwork(Bitmap originalMediaArtwork) {
        return MediaCardArtworkComposer.composeCard(
                originalMediaArtwork, repository.snapshot());
    }

    public Bitmap composeWarningOnlyArtwork(int size) {
        return MediaCardArtworkComposer.composeWarningWidget(size, repository.snapshot());
    }
}
