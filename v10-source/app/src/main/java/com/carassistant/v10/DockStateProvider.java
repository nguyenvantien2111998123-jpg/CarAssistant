package com.carassistant.v10;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

/**
 * Kênh IPC một chiều: app ghi `visibleUntil`, gearhead đọc.
 *
 * Chỉ đọc; caller phải là chính app hoặc Android Auto (gearhead).
 */
public final class DockStateProvider extends ContentProvider {

    public static final String AUTHORITY = "com.carassistant.v10.launcher.dock";
    public static final Uri STATE_URI = Uri.parse("content://" + AUTHORITY + "/state");

    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final String PREFS = "carassistant_projection_bootstrap";
    private static final String KEY_LAUNCH_REQUESTED_AT = "launch_requested_at";
    public static final String METHOD_REQUEST_PROJECTION_LAUNCH = "request_projection_launch";

    /** Epoch SystemClock.uptimeMillis(); 0 = không có lease. */
    public static volatile long visibleUntil;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!"/state".equals(uri.getPath())) {
            return null;
        }
        if (!isCallerAllowed()) {
            throw new SecurityException("Only Android Auto may read launcher state");
        }
        long launchRequestedAt = getContext().getSharedPreferences(PREFS, 0)
                .getLong(KEY_LAUNCH_REQUESTED_AT, 0L);
        MatrixCursor cursor = new MatrixCursor(new String[]{
                "visible_until", "projection_launch_requested_at"});
        cursor.addRow(new Object[]{visibleUntil, launchRequestedAt});
        return cursor;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_REQUEST_PROJECTION_LAUNCH.equals(method)) {
            return super.call(method, arg, extras);
        }
        // Only the app itself may request a launch. Gearhead is read-only.
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("Only CarAsistant may request projection launch");
        }
        long requestedAt = SystemClock.uptimeMillis();
        getContext().getSharedPreferences(PREFS, 0).edit()
                .putLong(KEY_LAUNCH_REQUESTED_AT, requestedAt).apply();
        Bundle result = new Bundle();
        result.putLong(KEY_LAUNCH_REQUESTED_AT, requestedAt);
        return result;
    }

    private boolean isCallerAllowed() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.myUid()) {
            return true;
        }
        String[] packages = getContext().getPackageManager().getPackagesForUid(callingUid);
        if (packages != null) {
            for (String pkg : packages) {
                if (GEARHEAD.equals(pkg)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/aacast-launcher-state";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
