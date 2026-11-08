package com.carassistant.v10;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.text.TextUtils;

import java.util.Arrays;

/**
 * Narrow IPC bridge for WYN's own Flutter asset requests. The caller must be the
 * installed WYN package; arbitrary apps cannot replace CarAsistant artwork.
 */
public final class WynWarningProvider extends ContentProvider {
    private static final String WYN_PACKAGE = "com.wynauto.wyn";
    private static final String METHOD_PUBLISH_ASSET = "publish_asset";

    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_PUBLISH_ASSET.equals(method) || extras == null) return new Bundle();
        Context context = getContext();
        if (context == null || !isWynCaller(context)) return rejected();

        String key = extras.getString("asset_key", "");
        if (!isAllowedKey(key)) return rejected();

        Bitmap warning = WynWarningAssetLoader.load(context, key);
        if (warning == null) return rejected();
        MediaSessionBridge.setWynWarningBitmap(warning);

        Bundle result = new Bundle();
        result.putBoolean("accepted", true);
        return result;
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
