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
        if (!"/state".equals(uri.getPath())) {
            return null;
        }
        if (!isCallerAllowed()) {
            throw new SecurityException("Only Android Auto may read launcher state");
        }
        String targetPackage = null;
        try {
            String flattened = getContext().getSharedPreferences("v10_session", 0)
                    .getString("target_component", null);
            android.content.ComponentName target = flattened == null ? null
                    : android.content.ComponentName.unflattenFromString(flattened);
            if (target != null) targetPackage = target.getPackageName();
        } catch (Throwable ignored) { }
        String mediaPackage = null;
        try {
            MediaSessionBridge.Snapshot snapshot = MediaSessionBridge.getSnapshot();
            if (snapshot != null && snapshot.packageName != null
                    && !snapshot.packageName.isEmpty()) mediaPackage = snapshot.packageName;
        } catch (Throwable ignored) { }
        boolean routeMediaClick = targetPackage != null && targetPackage.equals(mediaPackage);
        MatrixCursor cursor = new MatrixCursor(new String[]{
                "visible_until", "projection_active", "target_package", "active_media_package"});
        cursor.addRow(new Object[]{visibleUntil, routeMediaClick ? 1 : 0,
                targetPackage == null ? "" : targetPackage,
                mediaPackage == null ? "" : mediaPackage});
        return cursor;
    }

    private boolean isCallerAllowed() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.myUid() || callingUid == Process.SYSTEM_UID) {
            // Read-only routing state only; no write operations are exposed by this provider.
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
