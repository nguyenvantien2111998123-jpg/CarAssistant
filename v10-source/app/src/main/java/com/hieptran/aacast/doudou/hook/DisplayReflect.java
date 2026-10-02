package com.carassistant.v10.hook;

import android.view.Display;

import de.robv.android.xposed.XposedHelpers;

/**
 * Truy cập các API @hide của android.view.Display qua reflection.
 * (getOwnerPackageName / getType / getOwnerUid không có trong SDK public.)
 */
final class DisplayReflect {

    private DisplayReflect() {
    }

    static String ownerPackage(Display display) {
        try {
            return (String) XposedHelpers.callMethod(display, "getOwnerPackageName");
        } catch (Throwable t) {
            return null;
        }
    }

    static int type(Display display) {
        try {
            return (Integer) XposedHelpers.callMethod(display, "getType");
        } catch (Throwable t) {
            return -1;
        }
    }

    static int ownerUid(Display display) {
        try {
            return (Integer) XposedHelpers.callMethod(display, "getOwnerUid");
        } catch (Throwable t) {
            return -1;
        }
    }
}
