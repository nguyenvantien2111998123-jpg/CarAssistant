package com.carassistant.v10;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * Keeps WYN warning art separate from the source media artwork and composes
 * the two only at the CarAsistant presentation boundary.
 */
public final class WynWarningArtwork {
    private static volatile Bitmap currentWarning;

    private WynWarningArtwork() { }

    public static synchronized boolean setWarningBitmap(Bitmap source) {
        if (source == null || source.isRecycled()) return false;
        Bitmap copy;
        try {
            int width = source.getWidth();
            int height = source.getHeight();
            if (width <= 0 || height <= 0) return false;
            float scale = Math.min(1f, 512f / Math.max(width, height));
            copy = scale < 1f
                    ? Bitmap.createScaledBitmap(source,
                            Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)), true)
                    : source.copy(Bitmap.Config.ARGB_8888, false);
        } catch (RuntimeException error) {
            return false;
        }
        if (copy == null) return false;
        Bitmap previous = currentWarning;
        if (previous != null && !previous.isRecycled()
                && previous.getWidth() == copy.getWidth()
                && previous.getHeight() == copy.getHeight()) {
            try {
                if (previous.sameAs(copy)) {
                    if (copy != source && !copy.isRecycled()) copy.recycle();
                    return false;
                }
            } catch (RuntimeException ignored) { }
        }
        currentWarning = copy;
        return true;
    }

    public static Bitmap getWarningBitmap() {
        Bitmap value = currentWarning;
        return value == null || value.isRecycled() ? null : value;
    }

    /**
     * Android Auto's main artwork: warning occupies the top third; source media
     * artwork remains intact in the lower two thirds.
     */
    public static Bitmap composeMediaArtwork(Bitmap media, Bitmap warning) {
        if (warning == null || warning.isRecycled()) return media;
        final int width = 384;
        final int height = 384;
        final int warningHeight = height / 3;
        Bitmap output;
        try {
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException error) {
            return media;
        }
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.rgb(10, 13, 17));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG
                | Paint.DITHER_FLAG);

        RectF warningBounds = containBounds(warning, new RectF(8, 8, width - 8, warningHeight - 8));
        canvas.save();
        canvas.clipRect(0, 0, width, warningHeight);
        canvas.drawBitmap(warning, null, warningBounds, paint);
        canvas.restore();

        if (media != null && !media.isRecycled()) {
            RectF mediaBounds = new RectF(0, warningHeight, width, height);
            canvas.save();
            canvas.clipRect(mediaBounds);
            canvas.drawBitmap(media, null, centerCropBounds(media, mediaBounds), paint);
            canvas.restore();
        }
        return output;
    }

    /** Small Android Auto widget artwork: warning only, without media artwork. */
    public static Bitmap createWarningOnlyArtwork(Bitmap warning, int size) {
        if (warning == null || warning.isRecycled() || size <= 0) return null;
        try {
            Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG
                    | Paint.DITHER_FLAG);
            RectF bounds = containBounds(warning,
                    new RectF(4, 4, size - 4, size - 4));
            canvas.drawBitmap(warning, null, bounds, paint);
            return output;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static RectF containBounds(Bitmap bitmap, RectF target) {
        float scale = Math.min(target.width() / bitmap.getWidth(),
                target.height() / bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float left = target.centerX() - width / 2f;
        float top = target.centerY() - height / 2f;
        return new RectF(left, top, left + width, top + height);
    }

    private static RectF centerCropBounds(Bitmap bitmap, RectF target) {
        float scale = Math.max(target.width() / bitmap.getWidth(),
                target.height() / bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float left = target.centerX() - width / 2f;
        float top = target.centerY() - height / 2f;
        return new RectF(left, top, left + width, top + height);
    }
}
