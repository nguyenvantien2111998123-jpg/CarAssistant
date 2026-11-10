package com.carassistant.v10.engine.warning;

import android.graphics.Bitmap;

/**
 * WYN-specific adapter. It translates WYN's two established image channels
 * into the Core Warning Engine without depending on Flutter internals.
 *
 * The adapter is an ingress boundary, not a WYN data extractor: WYN must still
 * call the secured provider (or another verified transport) with real images.
 */
public final class WynWarningAdapter implements WarningDataSource {
    private final TrafficWarningEngine engine;

    public WynWarningAdapter() {
        this(TrafficWarningEngine.getInstance());
    }

    WynWarningAdapter(TrafficWarningEngine engine) {
        if (engine == null) throw new IllegalArgumentException("engine == null");
        this.engine = engine;
    }

    @Override public TrafficWarningSource source() {
        return TrafficWarningSource.WYN;
    }

    @Override public boolean publish(String warningCode, Bitmap image) {
        TrafficWarningType type = TrafficWarningType.fromCarStreamId(warningCode);
        if (type == null) return false;
        return engine.publishWarning(TrafficWarningSource.WYN, warningCode, image);
    }

    public boolean publishSpeedSign(Bitmap image) {
        return engine.updateSpeedSign(TrafficWarningSource.WYN, "speed_limit", image);
    }

    public boolean publishTemporaryAlert(String warningCode, Bitmap image) {
        TrafficWarningType type = TrafficWarningType.fromCarStreamId(warningCode);
        if (type == null || !type.isTemporary()) return false;
        return engine.updateTemporaryAlert(TrafficWarningSource.WYN, warningCode, image);
    }

    @Override public boolean clearTemporary() {
        return engine.clearTemporaryAlert(TrafficWarningSource.WYN);
    }

    public boolean clearSpeedSign() {
        return engine.clearSpeedSign(TrafficWarningSource.WYN);
    }

    @Override public boolean clear() {
        return engine.clearSource(TrafficWarningSource.WYN);
    }
}
