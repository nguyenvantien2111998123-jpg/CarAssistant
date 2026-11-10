package com.carassistant.v10.engine.warning;

import android.graphics.Bitmap;

/**
 * Compatibility adapter for the identifiers recovered from CarStream 1.24.57.
 * This adapter translates identifiers only; it is not a live CarStream transport.
 */
public final class CarStreamWarningAdapter implements WarningDataSource {
    private final TrafficWarningSource source;
    private final TrafficWarningEngine engine;

    public CarStreamWarningAdapter(TrafficWarningSource source) {
        this(source, TrafficWarningEngine.getInstance());
    }

    CarStreamWarningAdapter(TrafficWarningSource source, TrafficWarningEngine engine) {
        if (source == null) throw new IllegalArgumentException("source == null");
        if (engine == null) throw new IllegalArgumentException("engine == null");
        this.source = source;
        this.engine = engine;
    }

    @Override public TrafficWarningSource source() {
        return source;
    }

    @Override public boolean publish(String warningCode, Bitmap image) {
        if (TrafficWarningType.fromCarStreamId(warningCode) == null) return false;
        return engine.publishWarning(source, warningCode, image);
    }

    @Override public boolean clearTemporary() {
        return engine.clearTemporaryAlert(source);
    }

    @Override public boolean clear() {
        return engine.clearSource(source);
    }
}
