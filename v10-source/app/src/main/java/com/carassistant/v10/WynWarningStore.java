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
        warning = value.copy(Bitmap.Config.ARGB_8888, true);
        MediaSessionBridge.onWynWarningChanged();
        V10CarActivity.notifyWynWarningChanged();
    }

    /**
     * CarAsistant's square media artwork: keep the media cover as the background and
     * place the complete, aspect-preserved WYN popup as a compact top-left overlay.
     */
    public static Bitmap composeMediaArtwork(Bitmap media) {
        Bitmap alert = getWarning();
        if (alert == null) return media;
        final int size = 512;
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (media != null && !media.isRecycled()) {
            drawCenterCrop(canvas, media, new RectF(0, 0, size, size), paint);
        } else {
            canvas.drawColor(Color.rgb(28, 34, 40));
        }

        float margin = 14f;
        float maxWidth = size * 0.52f;
        float maxHeight = size * 0.52f;
        float scale = Math.min(maxWidth / alert.getWidth(), maxHeight / alert.getHeight());
        float width = Math.max(1f, alert.getWidth() * scale);
        float height = Math.max(1f, alert.getHeight() * scale);
        RectF destination = new RectF(margin, margin, margin + width, margin + height);
        Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
        panel.setColor(Color.argb(170, 10, 14, 18));
        canvas.drawRoundRect(new RectF(destination.left - 5f, destination.top - 5f,
                destination.right + 5f, destination.bottom + 5f), 10f, 10f, panel);
        canvas.drawBitmap(alert, null, destination, paint);
        return output;
    }

    /** Full popup bitmap for Android Auto's small widget artwork; no media cover and no crop. */
    public static Bitmap makeWidgetIcon() {
        Bitmap alert = getWarning();
        if (alert == null) return null;
        return alert.copy(Bitmap.Config.ARGB_8888, true);
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
