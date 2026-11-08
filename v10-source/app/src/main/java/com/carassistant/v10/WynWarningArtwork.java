package com.carassistant.v10;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/** Independent WYN speed-sign and temporary-alert artwork channels. */
public final class WynWarningArtwork {
    private static volatile Bitmap speedSign;
    private static volatile Bitmap temporaryAlert;

    private WynWarningArtwork() { }

    public static synchronized boolean setSpeedSignBitmap(Bitmap source) {
        Bitmap copy = copyForBridge(source);
        if (copy == null || sameBitmap(speedSign, copy)) {
            recycleIfOwned(copy, source);
            return false;
        }
        speedSign = copy;
        return true;
    }

    public static synchronized boolean setTemporaryAlertBitmap(Bitmap source) {
        Bitmap copy = copyForBridge(source);
        if (copy == null || sameBitmap(temporaryAlert, copy)) {
            recycleIfOwned(copy, source);
            return false;
        }
        temporaryAlert = copy;
        return true;
    }

    public static synchronized boolean clearTemporaryAlert() {
        if (temporaryAlert == null) return false;
        temporaryAlert = null;
        return true;
    }

    public static synchronized boolean clearAllWarnings() {
        boolean changed = speedSign != null || temporaryAlert != null;
        speedSign = null;
        temporaryAlert = null;
        return changed;
    }

    public static boolean hasWarnings() {
        return isUsable(speedSign) || isUsable(temporaryAlert);
    }

    /** Media card: warnings in the upper third, media artwork in the lower two thirds. */
    public static Bitmap composeMediaArtwork(Bitmap media) {
        Bitmap sign = isUsable(speedSign) ? speedSign : null;
        Bitmap alert = isUsable(temporaryAlert) ? temporaryAlert : null;
        if (sign == null && alert == null) return isUsable(media) ? media : null;

        final int width = 384, height = 384, warningHeight = height / 3;
        Bitmap output = createOutput(width, height);
        if (output == null) return isUsable(media) ? media : null;
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.rgb(10, 13, 17));
        Paint paint = warningPaint();
        drawWarningPair(canvas, sign, alert,
                new RectF(8, 8, width - 8, warningHeight - 8), paint);
        if (isUsable(media)) {
            RectF mediaBounds = new RectF(0, warningHeight, width, height);
            canvas.save();
            canvas.clipRect(mediaBounds);
            canvas.drawBitmap(media, null, centerCropBounds(media, mediaBounds), paint);
            canvas.restore();
        }
        return output;
    }

    /** Media widget: speed sign and temporary alert only. */
    public static Bitmap createWarningOnlyArtwork(int size) {
        if (size <= 0) return null;
        Bitmap sign = isUsable(speedSign) ? speedSign : null;
        Bitmap alert = isUsable(temporaryAlert) ? temporaryAlert : null;
        if (sign == null && alert == null) return null;
        Bitmap output = createOutput(size, size);
        if (output == null) return null;
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.rgb(10, 13, 17));
        drawWarningPair(canvas, sign, alert, new RectF(6, 6, size - 6, size - 6), warningPaint());
        return output;
    }

    private static void drawWarningPair(Canvas canvas, Bitmap sign, Bitmap alert,
                                        RectF target, Paint paint) {
        if (sign != null && alert != null) {
            float gap = Math.max(4f, target.width() * 0.025f);
            float half = (target.width() - gap) / 2f;
            drawContained(canvas, sign, new RectF(target.left, target.top,
                    target.left + half, target.bottom), paint);
            drawContained(canvas, alert, new RectF(target.left + half + gap,
                    target.top, target.right, target.bottom), paint);
        } else {
            drawContained(canvas, sign != null ? sign : alert, target, paint);
        }
    }

    private static void drawContained(Canvas canvas, Bitmap bitmap, RectF target, Paint paint) {
        if (!isUsable(bitmap)) return;
        float scale = Math.min(target.width() / bitmap.getWidth(),
                target.height() / bitmap.getHeight());
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        float left = target.centerX() - width / 2f, top = target.centerY() - height / 2f;
        canvas.drawBitmap(bitmap, null, new RectF(left, top, left + width, top + height), paint);
    }

    private static RectF centerCropBounds(Bitmap bitmap, RectF target) {
        float scale = Math.max(target.width() / bitmap.getWidth(),
                target.height() / bitmap.getHeight());
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        float left = target.centerX() - width / 2f, top = target.centerY() - height / 2f;
        return new RectF(left, top, left + width, top + height);
    }

    private static Bitmap copyForBridge(Bitmap source) {
        if (!isUsable(source)) return null;
        try {
            int width = source.getWidth(), height = source.getHeight();
            float scale = Math.min(1f, 512f / Math.max(width, height));
            return scale < 1f
                    ? Bitmap.createScaledBitmap(source, Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)), true)
                    : source.copy(Bitmap.Config.ARGB_8888, false);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static boolean sameBitmap(Bitmap current, Bitmap candidate) {
        if (!isUsable(current) || !isUsable(candidate)) return false;
        try {
            return current.getWidth() == candidate.getWidth()
                    && current.getHeight() == candidate.getHeight() && current.sameAs(candidate);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isUsable(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled()
                && bitmap.getWidth() > 0 && bitmap.getHeight() > 0;
    }

    private static Bitmap createOutput(int width, int height) {
        try {
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Paint warningPaint() {
        return new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    }

    private static void recycleIfOwned(Bitmap copy, Bitmap source) {
        if (copy != null && copy != source && !copy.isRecycled()) copy.recycle();
    }
}
