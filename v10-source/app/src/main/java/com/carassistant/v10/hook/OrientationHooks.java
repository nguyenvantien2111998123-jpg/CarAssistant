package com.carassistant.v10.hook;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.view.Display;
import android.view.Surface;

import com.carassistant.v10.AACastDisplay;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Optional landscape lock for CarAssistant's second virtual display.
 * The phone's primary display and other virtual displays are not modified.
 */
public final class OrientationHooks {
    private static final String TAG = "[AACast Orientation]";
    private static final String SETTING_FORCE_LANDSCAPE = "carassistant_force_landscape";

    private OrientationHooks() { }

    public static void install(ClassLoader classLoader) {
        try {
            Class<?> displayRotation = XposedHelpers.findClass(
                    "com.android.server.wm.DisplayRotation", classLoader);
            XposedBridge.hookAllMethods(displayRotation, "rotationForOrientation",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object displayContent = XposedHelpers.getObjectField(
                                        param.thisObject, "mDisplayContent");
                                int displayId = (Integer) XposedHelpers.callMethod(
                                        displayContent, "getDisplayId");
                                if (displayId <= 0) return;

                                Context context = (Context) XposedHelpers.getObjectField(
                                        param.thisObject, "mContext");
                                if (context == null) return;

                                DisplayManager manager = (DisplayManager) context.getSystemService(
                                        Context.DISPLAY_SERVICE);
                                Display display = manager == null ? null : manager.getDisplay(displayId);
                                if (display == null
                                        || !AACastDisplay.matches(
                                                DisplayReflect.ownerPackage(display),
                                                display.getName(),
                                                DisplayReflect.type(display),
                                                DisplayReflect.ownerUid(display))
                                        || !(AACastDisplay.PREFIX + "2").equals(display.getName())) {
                                    return;
                                }

                                if (Settings.Global.getInt(context.getContentResolver(),
                                        SETTING_FORCE_LANDSCAPE, 0) != 1) {
                                    return;
                                }

                                // The projection surface is created in landscape dimensions.
                                // Pin only CarAssistant's virtual display to rotation 0.
                                param.setResult(Surface.ROTATION_0);
                            } catch (Throwable t) {
                                // Keep Android's original rotation policy if this ROM differs.
                                XposedBridge.log(TAG + " Orientation policy unchanged: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + " Secondary-display landscape hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Hook unavailable: " + t);
        }
    }
}
