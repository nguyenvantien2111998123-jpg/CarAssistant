package com.carassistant.v10;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;

/** Read-only cross-process gate for media-card and steering-key routing. */
public final class MediaRouteState {
    private static final Uri STATE_URI = Uri.parse("content://com.carassistant.v10.launcher.dock/state");
    private MediaRouteState() { }

    public static boolean isActive(Context context) {
        if (context == null) return false;
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(STATE_URI,
                    new String[]{"projection_active", "target_package", "active_media_package"},
                    null, null, null);
            if (cursor == null || !cursor.moveToFirst() || cursor.getInt(0) != 1) return false;
            String target = cursor.getString(1);
            String media = cursor.getString(2);
            return !TextUtils.isEmpty(target) && target.equals(media);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Throwable ignored) { }
        }
    }
}
