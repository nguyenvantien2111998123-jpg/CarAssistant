package com.carassistant.v10;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Typeface;
import android.graphics.RectF;

import com.caverock.androidsvg.SVG;

import java.io.InputStream;

/** Loads the original warning illustrations bundled inside WYN 2.1.0. */
final class WynWarningAssetLoader {
    private static final String WYN_PACKAGE = "com.wynauto.wyn";
    private static final String ROOT = "flutter_assets/assets/images/components/";

    private WynWarningAssetLoader() { }

    static Bitmap load(Context context, String key) {
        if (context == null || key == null) return null;
        try {
            Context wyn = context.createPackageContext(
                    WYN_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            if (key.startsWith("road_sign_")) {
                return loadSvg(wyn.getAssets(), ROOT + key + ".svg");
            }
            if (key.startsWith("speed:")) {
                String speed = key.substring("speed:".length());
                return loadSpeedSign(wyn.getAssets(), speed);
            }
            if ("traffic_light".equals(key)) {
                return loadPng(wyn.getAssets(), ROOT + "horizontal-traffic-light.png");
            }
            if ("speed_camera".equals(key)) {
                return loadPng(wyn.getAssets(), ROOT + "speed_camera.png");
            }
        } catch (Throwable ignored) {
            // WYN may be temporarily unavailable while Android Auto is starting.
        }
        return null;
    }

    private static Bitmap loadSvg(AssetManager assets, String path) {
        try (InputStream input = assets.open(path)) {
            SVG svg = SVG.getFromInputStream(input);
            Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            svg.renderToCanvas(canvas, new RectF(0, 0, 256, 256));
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap loadPng(AssetManager assets, String path) {
        try (InputStream input = assets.open(path)) {
            return BitmapFactory.decodeStream(input);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap loadSpeedSign(AssetManager assets, String speedText) {
        Bitmap bitmap = loadPng(assets, ROOT + "settings/speed_sign.png");
        if (bitmap == null) return null;
        int speed;
        try {
            speed = Integer.parseInt(speedText);
        } catch (NumberFormatException error) {
            return bitmap;
        }
        if (speed == 50) return bitmap;

        Canvas canvas = new Canvas(bitmap);
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        float cx = bitmap.getWidth() / 2f;
        float cy = bitmap.getHeight() / 2f;
        canvas.drawCircle(cx, cy, bitmap.getWidth() * 0.235f, clear);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        text.setColor(Color.rgb(47, 174, 207));
        text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(speed >= 100 ? bitmap.getWidth() * 0.285f
                : bitmap.getWidth() * 0.34f);
        Paint.FontMetrics metrics = text.getFontMetrics();
        float baseline = cy - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(Integer.toString(speed), cx, baseline, text);
        return bitmap;
    }
}
