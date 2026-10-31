package com.carassistant.v10;

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

    public static final String AUTHORITY = "com.carassistant.v10.launcher.dock";
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
        if (!"/state".equals(uri.getPath()) && !"/media_resume".equals(uri.getPath())) {
            return null;
        }
        if (!isCallerAllowed()) {
            throw new SecurityException("Only Android Auto may read launcher state");
        }
        if ("/media_resume".equals(uri.getPath())) {
            android.content.SharedPreferences prefs = getContext().getSharedPreferences(
                    "carassistant_media_resume", android.content.Context.MODE_PRIVATE);
            MatrixCursor cursor = new MatrixCursor(new String[]{
                    "resume_valid", "resume_was_playing", "resume_saved_at"});
            cursor.addRow(new Object[]{
                    prefs.getBoolean("resume_valid", false) ? 1 : 0,
                    prefs.getBoolean("resume_was_playing", false) ? 1 : 0,
                    prefs.getLong("resume_saved_at", 0L)
            });
            return cursor;
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
