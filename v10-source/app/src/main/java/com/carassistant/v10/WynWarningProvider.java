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

/**
 * Narrow IPC bridge for WYN's own Flutter asset requests. The caller must be the
 * installed WYN package; arbitrary apps cannot replace CarAsistant artwork.
 */
public final class WynWarningProvider extends ContentProvider {
    private static final String WYN_PACKAGE = "com.wynauto.wyn";
    private static final String METHOD_PUBLISH_ASSET = "publish_asset";
    private static final String METHOD_PUBLISH_WARNING = "publish_warning";
    private static final String METHOD_PUBLISH_BITMAP = "publish_bitmap";
    private static final String METHOD_CLEAR_TEMPORARY = "clear_temporary_alert";

    @Override public boolean onCreate() {
        if (getContext() != null) {
            TrafficWarningEngine.getInstance().initialize(getContext());
        }
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null) return rejected();
        Context context = getContext();
        if (context == null || !isWynCaller(context)) return rejected();

        if (METHOD_CLEAR_TEMPORARY.equals(method)) {
            MediaSessionBridge.clearWynTemporaryAlert();
            return accepted();
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
            if (liveBitmap == null || liveBitmap.isRecycled()
                    || liveBitmap.getWidth() <= 0 || liveBitmap.getHeight() <= 0
                    || liveBitmap.getWidth() > 256 || liveBitmap.getHeight() > 256) {
                return rejected();
            }
            if ("speed_sign".equals(bitmapType)) {
                MediaSessionBridge.setWynSpeedSignBitmap(liveBitmap);
            } else {
                MediaSessionBridge.setWynTemporaryAlertBitmap(liveBitmap);
            }
            return accepted();
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
        if (warning == null) return rejected();
        // The numeric speed-limit artwork is a separate channel. Road-sign SVGs,
        // traffic lights and speed-camera images are transient alerts; treating every
        // road_sign_* asset as the permanent speed sign caused the two channels to swap.
        if ("speed_sign".equals(warningType)
                || (METHOD_PUBLISH_ASSET.equals(method) && key.startsWith("speed:"))) {
            MediaSessionBridge.setWynSpeedSignBitmap(warning);
        } else {
            MediaSessionBridge.setWynTemporaryAlertBitmap(warning);
        }
        return accepted();
    }

    private static boolean isWynCaller(Context context) {
        String[] packages = context.getPackageManager()
                .getPackagesForUid(Binder.getCallingUid());
        return packages != null && Arrays.asList(packages).contains(WYN_PACKAGE);
    }

    private static boolean isAllowedKey(String key) {
        if (TextUtils.isEmpty(key)) return false;
        if ("traffic_light".equals(key) || "speed_camera".equals(key)
                || key.matches("speed:[0-9]{2,3}")) return true;
        return key.matches("road_sign_(?i:DP|P|R)[0-9]{2,3}[A-Za-z]?[0-9]?");
    }

    private static Bundle accepted() {
        Bundle result = new Bundle();
        result.putBoolean("accepted", true);
        return result;
    }

    private static Bundle rejected() {
        Bundle result = new Bundle();
        result.putBoolean("accepted", false);
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
