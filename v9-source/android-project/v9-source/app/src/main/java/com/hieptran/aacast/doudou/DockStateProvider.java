package com.carassistant.v9;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

/**
 * Kênh IPC một chiều: app ghi `visibleUntil`, gearhead đọc.
 *
 * Chỉ đọc; caller phải là chính app hoặc Android Auto (gearhead).
 */
public final class DockStateProvider extends ContentProvider {

    public static final String AUTHORITY = "com.carassistant.v9.launcher.dock";
    public static final Uri STATE_URI = Uri.parse("content://" + AUTHORITY + "/state");

    private static final String GEARHEAD = "com.google.android.projection.gearhead";

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
        MatrixCursor cursor = new MatrixCursor(new String[]{"visible_until"});
        cursor.addRow(new Object[]{visibleUntil});
        return cursor;
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
