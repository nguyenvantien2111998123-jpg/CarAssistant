package com.carassistant.v10.engine.warning;

/**
 * Traffic-source identifiers recovered from CarStream 1.24.57.
 *
 * CarStream's APK stores these identifiers in its source selector. They are kept
 * here as stable adapter IDs; "carstream" is deliberately not a source in
 * CarAsistant.
 */
public enum TrafficWarningSource {
    WYN("wyn", "WYN", true,
            "WYN 2.1.0. CarAsistant retains the existing WYN image bridge; live Flutter-frame capture must still be validated on the POCO F1."),
    VIETMAP_WIDGET("widget", "VietMap Widget", false,
            "CarStream identifies VietMap as the widget source. The uploaded Full APK does not contain the widget receiver/bridge."),
    GOFA("z23e037d98", "Gofa", false,
            "CarStream exposes Gofa as a selectable source. Its acquisition adapter is not present in the uploaded Full APK."),
    WAZE("zda617cedc", "Waze", false,
            "CarStream exposes Waze as a selectable source. Its acquisition adapter is not present in the uploaded Full APK."),
    WAZE_HLP("z9e4343e4b", "Waze HLP", false,
            "CarStream documents a Waze-mod WiFi WebSocket endpoint at 127.0.0.1:8765/hlp; the message schema is not present in the uploaded Full APK.");

    private final String carStreamId;
    private final String displayName;
    private final boolean enabledByDefault;
    private final String integrationNote;

    TrafficWarningSource(String carStreamId, String displayName,
                         boolean enabledByDefault, String integrationNote) {
        this.carStreamId = carStreamId;
        this.displayName = displayName;
        this.enabledByDefault = enabledByDefault;
        this.integrationNote = integrationNote;
    }

    public String getCarStreamId() {
        return carStreamId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isEnabledByDefault() {
        return enabledByDefault;
    }

    public String getIntegrationNote() {
        return integrationNote;
    }

    public static TrafficWarningSource fromCarStreamId(String id) {
        if (id == null) return null;
        for (TrafficWarningSource source : values()) {
            if (source.carStreamId.equals(id)) return source;
        }
        return null;
    }
}
