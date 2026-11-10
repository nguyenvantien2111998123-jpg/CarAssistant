package com.carassistant.v10.engine.warning;

import android.graphics.Bitmap;

/**
 * Adapter boundary between an external warning producer and the normalized
 * Core Warning Engine. Implementations must not fabricate missing source data.
 */
public interface WarningDataSource {
    TrafficWarningSource source();

    /** Publish a CarAsistant-normalized warning code and its real source image. */
    boolean publish(String warningCode, Bitmap image);

    /** Clear only the temporary warning; persistent speed-sign state is retained. */
    boolean clearTemporary();

    /** Clear all warning channels owned by this source. */
    boolean clear();
}
