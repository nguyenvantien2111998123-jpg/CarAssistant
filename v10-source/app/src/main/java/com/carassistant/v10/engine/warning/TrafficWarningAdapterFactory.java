package com.carassistant.v10.engine.warning;

import android.graphics.Bitmap;

/** Creates the source-specific adapter without leaking transport details into Core. */
public final class TrafficWarningAdapterFactory {
    private TrafficWarningAdapterFactory() { }

    public static WarningDataSource forSource(TrafficWarningSource source) {
        if (source == null) return null;
        if (source == TrafficWarningSource.WYN) return new WynWarningAdapter();
        return new CarStreamWarningAdapter(source);
    }

    public static boolean publish(
            TrafficWarningSource source, String externalWarningId, Bitmap image) {
        WarningDataSource adapter = forSource(source);
        return adapter != null && adapter.publish(externalWarningId, image);
    }

    public static boolean clearTemporary(TrafficWarningSource source) {
        WarningDataSource adapter = forSource(source);
        return adapter != null && adapter.clearTemporary();
    }

    public static boolean clear(TrafficWarningSource source) {
        WarningDataSource adapter = forSource(source);
        return adapter != null && adapter.clear();
    }
}
