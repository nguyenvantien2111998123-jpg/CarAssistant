package com.carassistant.v10.engine.warning;

import android.content.Context;
import android.graphics.Bitmap;

import com.carassistant.v10.engine.artwork.MediaCardArtworkComposer;

import java.util.Set;

/**
 * Process-wide, multi-source warning engine. Source adapters publish normalized
 * warning images here; this engine does not invent or scrape source protocols.
 */
public final class TrafficWarningEngine {
    private static final TrafficWarningEngine INSTANCE = new TrafficWarningEngine();

    private final WarningStateRepository repository = new WarningStateRepository();
    private volatile boolean initialized;

    private TrafficWarningEngine() { }

    public static TrafficWarningEngine getInstance() {
        return INSTANCE;
    }

    /** Load the user's independent source toggles once an Android Context exists. */
    public synchronized void initialize(Context context) {
        if (context == null || initialized) return;
        repository.setEnabledSources(
                TrafficWarningSourceSettings.getEnabledSources(context));
        initialized = true;
    }

    public synchronized boolean setSourceEnabled(
            Context context, TrafficWarningSource source, boolean enabled) {
        if (context == null || source == null) return false;
        initialize(context);
        if (!TrafficWarningSourceSettings.setEnabled(context, source, enabled)) {
            // A false return from commit() can mean persistence failed; do not change runtime state.
            return repository.isSourceEnabled(source) == enabled;
        }
        return repository.setSourceEnabled(source, enabled)
                || repository.isSourceEnabled(source) == enabled;
    }

    public boolean isSourceEnabled(TrafficWarningSource source) {
        return repository.isSourceEnabled(source);
    }

    public Set<TrafficWarningSource> getEnabledSources() {
        return repository.enabledSources();
    }

    /** Backward-compatible WYN entry point. */
    public boolean updateSpeedSign(Bitmap bitmap) {
        return updateSpeedSign(TrafficWarningSource.WYN, "speed_limit", bitmap);
    }

    public boolean updateSpeedSign(
            TrafficWarningSource source, String code, Bitmap bitmap) {
        return repository.setSpeedSign(source, code, bitmap);
    }

    /** Backward-compatible WYN entry point. */
    public boolean updateTemporaryAlert(Bitmap bitmap) {
        return updateTemporaryAlert(TrafficWarningSource.WYN, null, bitmap);
    }

    public boolean updateTemporaryAlert(
            TrafficWarningSource source, String code, Bitmap bitmap) {
        return repository.setTemporaryAlert(source, code, bitmap);
    }

    /**
     * Normalize a CarStream warning ID into the persistent speed-limit channel or
     * the temporary-alert channel. Unknown IDs are rejected rather than guessed.
     */
    public boolean publishWarning(
            TrafficWarningSource source, String carStreamWarningId, Bitmap bitmap) {
        if (source == null || carStreamWarningId == null) return false;
        TrafficWarningType type = TrafficWarningType.fromCarStreamId(carStreamWarningId);
        if (type == null || !isSourceEnabled(source)) return false;
        return type.isTemporary()
                ? updateTemporaryAlert(source, carStreamWarningId, bitmap)
                : updateSpeedSign(source, carStreamWarningId, bitmap);
    }

    public boolean clearTemporaryAlert() {
        return clearTemporaryAlert(TrafficWarningSource.WYN);
    }

    public boolean clearTemporaryAlert(TrafficWarningSource source) {
        return repository.clearTemporaryAlert(source);
    }

    public boolean clearSpeedSign(TrafficWarningSource source) {
        return repository.clearSpeedSign(source);
    }

    public boolean clearSource(TrafficWarningSource source) {
        return repository.clearSource(source);
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
