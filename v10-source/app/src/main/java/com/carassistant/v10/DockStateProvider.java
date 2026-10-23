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
        android.content.Context context = getContext();
        String targetPackage = "";
        boolean v10MediaRouteActive = false;
        if (context != null) {
            android.content.ComponentName target = V10SessionStore.getTarget(context);
            MediaSessionBridge.Snapshot snapshot = MediaSessionBridge.getSnapshot();
            if (target != null) {
                targetPackage = target.getPackageName();
                v10MediaRouteActive = snapshot != null
                        && targetPackage.equals(snapshot.packageName);
            }
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{
                "visible_until", "target_package", "v10_media_route_active"});
        cursor.addRow(new Object[]{visibleUntil, targetPackage, v10MediaRouteActive ? 1 : 0});
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
