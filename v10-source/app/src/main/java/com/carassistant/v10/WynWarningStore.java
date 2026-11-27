package com.carassistant.v10;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/** WYN sign crop and popup-over-media artwork shared by the AA card and widget. */
public final class WynWarningStore {
    private static volatile Bitmap warning;
    private WynWarningStore() { }

    public static Bitmap getWarning() {
        Bitmap value = warning;
        return value != null && !value.isRecycled() ? value : null;
    }

    public static synchronized void setWarning(Bitmap value) {
        if (value == null || value.isRecycled()) return;
        warning = cropLowerSignHalf(value);
        MediaSessionBridge.onWynWarningChanged();
        V10CarActivity.notifyWynWarningChanged();
    }

    public static Bitmap composeMediaArtwork(Bitmap media) {
        return composeMediaArtwork(media, 52, 50, 50);
    }

    public static Bitmap composeMediaArtwork(Bitmap media, int overlayPercent) {
        return composeMediaArtwork(media, overlayPercent, 50, 50);
    }

    /** Draw the sign-only popup over the media cover, with user-adjustable size and position. */
    public static Bitmap composeMediaArtwork(
            Bitmap media, int overlayPercent, int xPercent, int yPercent) {
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
        int percent = Math.max(20, Math.min(80, overlayPercent));
        float maxWidth = size * percent / 100f;
        float maxHeight = size * 0.82f;
        float scale = Math.min(maxWidth / alert.getWidth(), maxHeight / alert.getHeight());
        float width = Math.max(1f, alert.getWidth() * scale);
        float height = Math.max(1f, alert.getHeight() * scale);
        float x = Math.max(0, Math.min(100, xPercent)) / 100f;
        float y = Math.max(0, Math.min(100, yPercent)) / 100f;
        float left = (size - width) * x;
        float top = (size - height) * y;
        canvas.drawBitmap(alert, null, new RectF(left, top, left + width, top + height), paint);
        return output;
    }

    /** The existing draggable Touch button keeps the sign-only warning image. */
    public static Bitmap makeTouchIcon() {
        Bitmap alert = getWarning();
        return alert == null ? null : alert.copy(Bitmap.Config.ARGB_8888, true);
    }

    /** The speed display is above the warning sign in WYN's popup: keep only the lower half. */
    private static Bitmap cropLowerSignHalf(Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        int width = source.getWidth();
        int height = source.getHeight();
        if (width < 80 || height < 120) return source.copy(Bitmap.Config.ARGB_8888, true);
        int top = height / 2;
        try {
            return Bitmap.createBitmap(source, 0, top, width, height - top)
                    .copy(Bitmap.Config.ARGB_8888, true);
        } catch (RuntimeException error) {
            return source.copy(Bitmap.Config.ARGB_8888, true);
        }
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
