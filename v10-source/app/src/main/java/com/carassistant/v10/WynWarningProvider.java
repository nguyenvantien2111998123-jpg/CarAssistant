package com.carassistant.v10;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import java.util.Arrays;

import com.carassistant.v10.engine.warning.TrafficWarningEngine;
import com.carassistant.v10.engine.warning.WynWarningAdapter;

/**
 * Secured IPC ingress for WYN warning images.
 *
 * This provider validates caller identity and payload shape, then delegates
 * classification/state changes to WynWarningAdapter. It does not inspect or
 * hook WYN's Flutter rendering pipeline.
 */
public final class WynWarningProvider extends ContentProvider {
    private static final String WYN_PACKAGE = "com.wynauto.wyn";
    private static final String METHOD_PUBLISH_ASSET = "publish_asset";
    private static final String METHOD_PUBLISH_WARNING = "publish_warning";
    private static final String METHOD_PUBLISH_BITMAP = "publish_bitmap";
    private static final String METHOD_CLEAR_TEMPORARY = "clear_temporary_alert";

    private WynWarningAdapter adapter;

    @Override public boolean onCreate() {
        if (getContext() != null) {
            TrafficWarningEngine.getInstance().initialize(getContext());
            adapter = new WynWarningAdapter();
        }
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null || adapter == null) return rejected();
        Context context = getContext();
        if (context == null || !isWynCaller(context)) return rejected();

        if (METHOD_CLEAR_TEMPORARY.equals(method)) {
            boolean changed = adapter.clearTemporary();
            if (changed) MediaSessionBridge.refreshTrafficWarningArtwork();
            return result(changed);
        }

        if (METHOD_PUBLISH_BITMAP.equals(method)) {
            String bitmapType = extras.getString("warning_type", "");
            if (!("speed_sign".equals(bitmapType)
                    || "temporary_alert".equals(bitmapType))) return rejected();
            Bitmap liveBitmap;
            if (Build.VERSION.SDK_INT >= 33) {
                liveBitmap = extras.getParcelable("warning_bitmap", Bitmap.class);
            } else {
                //noinspection deprecation
                liveBitmap = (Bitmap) extras.getParcelable("warning_bitmap");
            }
            if (!isValidBitmap(liveBitmap)) return rejected();
            boolean changed = "speed_sign".equals(bitmapType)
                    ? adapter.publishSpeedSign(liveBitmap)
                    : adapter.publishTemporaryImage(liveBitmap);
            if (changed) MediaSessionBridge.refreshTrafficWarningArtwork();
            return result(changed);
        }

        String key = extras.getString("asset_key", "");
        if (!isAllowedKey(key)) return rejected();
        String warningType = extras.getString("warning_type", "");
        if (METHOD_PUBLISH_WARNING.equals(method)
                && !("speed_sign".equals(warningType)
                || "temporary_alert".equals(warningType))) return rejected();
        if (!METHOD_PUBLISH_WARNING.equals(method)
                && !METHOD_PUBLISH_ASSET.equals(method)) return rejected();

        Bitmap warning = WynWarningAssetLoader.load(context, key);
        if (!isValidBitmap(warning)) return rejected();

        boolean speedSign = "speed_sign".equals(warningType)
                || (METHOD_PUBLISH_ASSET.equals(method) && key.startsWith("speed:"));
        boolean changed = speedSign
                ? adapter.publishSpeedSign(warning)
                : adapter.publishTemporaryImage(warning);
        if (changed) MediaSessionBridge.refreshTrafficWarningArtwork();
        return result(changed);
    }

    private static boolean isWynCaller(Context context) {
        String[] packages = context.getPackageManager()
                .getPackagesForUid(Binder.getCallingUid());
        return packages != null && Arrays.asList(packages).contains(WYN_PACKAGE);
    }

    private static boolean isValidBitmap(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled()
                && bitmap.getWidth() > 0 && bitmap.getHeight() > 0
                && bitmap.getWidth() <= 256 && bitmap.getHeight() <= 256;
    }

    private static boolean isAllowedKey(String key) {
        if (TextUtils.isEmpty(key)) return false;
        if ("traffic_light".equals(key) || "speed_camera".equals(key)
                || key.matches("speed:[0-9]{2,3}")) return true;
        return key.matches("road_sign_(?i:DP|P|R)[0-9]{2,3}[A-Za-z]?[0-9]?");
    }

    private static Bundle result(boolean changed) {
        Bundle result = new Bundle();
        result.putBoolean("accepted", changed);
        return result;
    }

    private static Bundle rejected() {
        return result(false);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
