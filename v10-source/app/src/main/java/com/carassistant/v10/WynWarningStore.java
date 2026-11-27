package com.carassistant.v10;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/** Shared WYN warning bitmap cache and independent Touch/card/widget artwork renderers. */
public final class WynWarningStore {
    private static volatile Bitmap warning;

    private WynWarningStore() { }

    public static Bitmap getWarning() {
        Bitmap value = warning;
        return value != null && !value.isRecycled() ? value : null;
    }

    public static synchronized void setWarning(Bitmap value) {
        if (value == null || value.isRecycled()) return;
        // Keep the previous immutable frame alive for any MediaSession/UI consumer
        // still holding its reference; the replacement is reclaimed by normal GC.
        warning = cropPopupHeaderAndTicker(value);
        MediaSessionBridge.onWynWarningChanged();
        V10CarActivity.notifyWynWarningChanged();
    }

    /**
     * Media card artwork: warning occupies a centered full-width top band (one third);
     * the media cover remains visible in the lower two thirds.
     */
    public static Bitmap composeMediaArtwork(Bitmap media) {
        return composeMediaArtwork(media, 52);
    }

    public static Bitmap composeMediaArtwork(Bitmap media, int overlayPercent) {
        Bitmap alert = getWarning();
        if (alert == null) return media;
        final int size = 512;
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (media != null && !media.isRecycled()) {
            drawCenterCrop(canvas, media, new RectF(0, 0, size, size), paint);
        } else {
            canvas.drawColor(Color.rgb(17, 22, 28));
        }
        final float bandHeight = size / 3f;
        final float margin = 12f;
        Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
        panel.setColor(Color.argb(238, 8, 12, 16));
        canvas.drawRect(0, 0, size, bandHeight, panel);
        float scale = Math.min((size - margin * 2f) / alert.getWidth(),
                (bandHeight - margin * 2f) / alert.getHeight());
        float width = Math.max(1f, alert.getWidth() * scale);
        float height = Math.max(1f, alert.getHeight() * scale);
        float left = (size - width) / 2f;
        float top = (bandHeight - height) / 2f;
        canvas.drawBitmap(alert, null, new RectF(left, top, left + width, top + height), paint);
        return output;
    }

    /** Widget uses the full warning bitmap in its original aspect ratio, not card art. */
    public static Bitmap makeWidgetIcon() {
        Bitmap alert = getWarning();
        return alert == null ? null : alert.copy(Bitmap.Config.ARGB_8888, true);
    }

    /** Remove WYN's branded top header and scrolling footer from the captured popup. */
    private static Bitmap cropPopupHeaderAndTicker(Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        int width = source.getWidth();
        int height = source.getHeight();
        if (width < 80 || height < 120 || height < width * 1.2f) {
            return source.copy(Bitmap.Config.ARGB_8888, true);
        }
        int top = Math.round(height * 0.12f);
        int bottom = Math.round(height * 0.10f);
        try {
            return Bitmap.createBitmap(source, 0, top, width, Math.max(1, height - top - bottom))
                    .copy(Bitmap.Config.ARGB_8888, true);
        } catch (RuntimeException error) {
            return source.copy(Bitmap.Config.ARGB_8888, true);
        }
    }

    /** Full popup bitmap for the existing draggable Touch surface; preserve alpha and aspect. */
    public static Bitmap makeTouchIcon() {
        Bitmap alert = getWarning();
        if (alert == null) return null;
        return alert.copy(Bitmap.Config.ARGB_8888, true);
    }

    private static void drawCenterCrop(Canvas canvas, Bitmap bitmap, RectF destination, Paint paint) {
        float scale = Math.max(destination.width() / bitmap.getWidth(),
                destination.height() / bitmap.getHeight());
        float width = destination.width() / scale;
        float height = destination.height() / scale;
        float left = (bitmap.getWidth() - width) / 2f;
        float top = (bitmap.getHeight() - height) / 2f;
        android.graphics.Rect source = new android.graphics.Rect(
                Math.max(0, (int) left), Math.max(0, (int) top),
                Math.min(bitmap.getWidth(), (int) Math.ceil(left + width)),
                Math.min(bitmap.getHeight(), (int) Math.ceil(top + height)));
        canvas.drawBitmap(bitmap, source, destination, paint);
    }
}
