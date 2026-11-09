package com.carassistant.v10.engine.artwork;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import com.carassistant.v10.engine.warning.WarningStateRepository;

/** Renders the WYN warning strip and original media artwork without distorting either. */
public final class MediaCardArtworkComposer {
    private static final int CARD_SIZE = 384;
    private static final int WARNING_HEIGHT = CARD_SIZE / 3;

    private MediaCardArtworkComposer() { }

    /** Top one-third: active WYN warning. Bottom two-thirds: original media artwork. */
    public static Bitmap composeCard(Bitmap mediaArtwork,
                                     WarningStateRepository.Snapshot warningState) {
        Bitmap warning = warningState == null ? null : warningState.activeWarning();
        if (!isUsable(warning)) return isUsable(mediaArtwork) ? mediaArtwork : null;

        Bitmap output = createBitmap(CARD_SIZE, CARD_SIZE);
        if (output == null) return isUsable(mediaArtwork) ? mediaArtwork : null;

        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.rgb(10, 13, 17));
        Paint paint = paint();
        drawContained(canvas, warning,
                new RectF(8f, 8f, CARD_SIZE - 8f, WARNING_HEIGHT - 8f), paint);

        if (isUsable(mediaArtwork)) {
            Rect source = centerCropSource(mediaArtwork, CARD_SIZE,
                    CARD_SIZE - WARNING_HEIGHT);
            Rect destination = new Rect(0, WARNING_HEIGHT, CARD_SIZE, CARD_SIZE);
            canvas.drawBitmap(mediaArtwork, source, destination, paint);
        }
        return output;
    }

    /** Android Auto's small widget artwork: active warning only, never media artwork. */
    public static Bitmap composeWarningWidget(int size,
                                              WarningStateRepository.Snapshot warningState) {
        if (size <= 0 || warningState == null) return null;
        Bitmap warning = warningState.activeWarning();
        if (!isUsable(warning)) return null;

        Bitmap output = createBitmap(size, size);
        if (output == null) return null;
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.rgb(10, 13, 17));
        drawContained(canvas, warning,
                new RectF(6f, 6f, size - 6f, size - 6f), paint());
        return output;
    }

    private static void drawContained(Canvas canvas, Bitmap bitmap,
                                      RectF bounds, Paint paint) {
        if (!isUsable(bitmap) || bounds.width() <= 0f || bounds.height() <= 0f) return;
        float scale = Math.min(bounds.width() / bitmap.getWidth(),
                bounds.height() / bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float left = bounds.centerX() - width / 2f;
        float top = bounds.centerY() - height / 2f;
        canvas.drawBitmap(bitmap, null,
                new RectF(left, top, left + width, top + height), paint);
    }

    /** Returns a centered source crop matching the destination aspect ratio. */
    private static Rect centerCropSource(Bitmap bitmap, int destinationWidth,
                                         int destinationHeight) {
        float sourceAspect = (float) bitmap.getWidth() / (float) bitmap.getHeight();
        float destinationAspect = (float) destinationWidth / (float) destinationHeight;
        int left = 0;
        int top = 0;
        int right = bitmap.getWidth();
        int bottom = bitmap.getHeight();
        if (sourceAspect > destinationAspect) {
            int cropWidth = Math.max(1,
                    Math.min(bitmap.getWidth(), Math.round(bitmap.getHeight() * destinationAspect)));
            left = (bitmap.getWidth() - cropWidth) / 2;
            right = left + cropWidth;
        } else if (sourceAspect < destinationAspect) {
            int cropHeight = Math.max(1,
                    Math.min(bitmap.getHeight(), Math.round(bitmap.getWidth() / destinationAspect)));
            top = (bitmap.getHeight() - cropHeight) / 2;
            bottom = top + cropHeight;
        }
        return new Rect(left, top, right, bottom);
    }

    private static Bitmap createBitmap(int width, int height) {
        try {
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Paint paint() {
        return new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    }

    private static boolean isUsable(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled()
                && bitmap.getWidth() > 0 && bitmap.getHeight() > 0;
    }
}
