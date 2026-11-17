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
 * IPC between CarAsistant and the LSPosed hook running inside Gearhead.
 * Gearhead can read dock state and report its actual projection-host lifecycle.
 */
public final class DockStateProvider extends ContentProvider {

    public static final String AUTHORITY = "com.carassistant.v10.launcher.dock";
    public static final Uri STATE_URI = Uri.parse("content://" + AUTHORITY + "/state");

    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final String PREFS = "carassistant_projection_bootstrap";
    private static final String KEY_LAUNCH_REQUESTED_AT = "launch_requested_at";
    public static final String METHOD_REQUEST_PROJECTION_LAUNCH = "request_projection_launch";
    public static final String METHOD_REPORT_AA_HOST_STATE = "report_android_auto_host_state";

    /** Epoch SystemClock.uptimeMillis(); 0 = no active dock lease. */
    public static volatile long visibleUntil;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (!"/state".equals(uri.getPath())) return null;
        if (!isGearheadOrSelf()) {
            throw new SecurityException("Only CarAsistant and Android Auto may read launcher state");
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
        if (METHOD_REQUEST_PROJECTION_LAUNCH.equals(method)) {
            if (Binder.getCallingUid() != Process.myUid()) {
                throw new SecurityException("Only CarAsistant may request a projection launch");
            }
            long requestedAt = SystemClock.uptimeMillis();
            getContext().getSharedPreferences(PREFS, 0).edit()
                    .putLong(KEY_LAUNCH_REQUESTED_AT, requestedAt).apply();
            Bundle result = new Bundle();
            result.putLong(KEY_LAUNCH_REQUESTED_AT, requestedAt);
            return result;
        }

        if (METHOD_REPORT_AA_HOST_STATE.equals(method)) {
            if (!isGearheadCaller()) {
                throw new SecurityException("Only Android Auto Gearhead may report host state");
            }
            if (!"connected".equals(arg) && !"disconnected".equals(arg)) {
                throw new IllegalArgumentException("Host state must be connected or disconnected");
            }
            boolean connected = "connected".equals(arg);
            CarAssistantCore.startRecoveryMonitor(getContext().getApplicationContext(),
                    "GearheadProjectionHost");
            CarAssistantCore.reportAndroidAutoConnection(connected);
            Bundle result = new Bundle();
            result.putBoolean("accepted", true);
            result.putBoolean("connected", connected);
            return result;
        }

        return super.call(method, arg, extras);
    }

    private boolean isGearheadOrSelf() {
        return Binder.getCallingUid() == Process.myUid() || isGearheadCaller();
    }

    private boolean isGearheadCaller() {
        int callingUid = Binder.getCallingUid();
        String[] packages = getContext().getPackageManager().getPackagesForUid(callingUid);
        if (packages == null) return false;
        for (String pkg : packages) {
            if (GEARHEAD.equals(pkg)) return true;
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
