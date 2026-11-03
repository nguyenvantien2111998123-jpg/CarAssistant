package com.carassistant.v10.hook;

import android.view.Display;

import com.carassistant.v10.AACastDisplay;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook trên system_server:
 *  1. Thêm FLAG_OWN_DISPLAY_GROUP | FLAG_ALWAYS_UNLOCKED cho display ảo của app
 *     (display vẫn "sống" khi màn hình điện thoại tắt — trạng thái projection).
 *  2. Cho phép dispatch input tới display đó khi màn hình không tương tác.
 */
public final class DisplayHooks {

    private static final String TAG_DISPLAY = "[AACast Display]";

    private DisplayHooks() {
    }

    /**
     * Android 12 checks virtual-display embedding/private-display policy before
     * accepting --display. Permit only the root shell used by CarAsistant's own
     * su session, and only for a virtual display whose owner/name match V10.
     * This deliberately does not grant the app UID, shell UID, or other displays
     * a general activity-launch bypass.
     */
    public static void installV10RootLaunchPolicy(ClassLoader classLoader) {
        try {
            Class<?> supervisor = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityTaskSupervisor", classLoader);
            Class<?> activityInfo = XposedHelpers.findClass(
                    "android.content.pm.ActivityInfo", classLoader);
            XposedHelpers.findAndHookMethod(
                    supervisor,
                    "isCallerAllowedToLaunchOnDisplay",
                    int.class, int.class, int.class, activityInfo,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable() || Boolean.TRUE.equals(param.getResult())) return;
                            int callingUid = (Integer) param.args[1];
                            int displayId = (Integer) param.args[2];
                            // RootShellSession starts the am process from a su-root process.
                            if (callingUid != 0 || displayId <= 0) return;
                            try {
                                Object root = XposedHelpers.getObjectField(
                                        param.thisObject, "mRootWindowContainer");
                                Object content = XposedHelpers.callMethod(
                                        root, "getDisplayContentOrCreate", displayId);
                                if (content == null) return;
                                Object rawDisplay = XposedHelpers.getObjectField(content, "mDisplay");
                                if (!(rawDisplay instanceof Display)) return;
                                Display display = (Display) rawDisplay;
                                if (!AACastDisplay.matches(
                                        DisplayReflect.ownerPackage(display),
                                        display.getName(),
                                        DisplayReflect.type(display),
                                        DisplayReflect.ownerUid(display))) return;
                                param.setResult(Boolean.TRUE);
                                XposedBridge.log(TAG_DISPLAY
                                        + " Root launch allowed on owned V10 display only: "
                                        + displayId + " / " + display.getName());
                            } catch (Throwable error) {
                                XposedBridge.log(TAG_DISPLAY
                                        + " Root launch policy check failed closed: " + error);
                            }
                        }
                    });
            XposedBridge.log(TAG_DISPLAY + " V10-only root activity launch policy installed");
        } catch (Throwable error) {
            XposedBridge.log(TAG_DISPLAY + " V10 root launch policy unavailable: " + error);
        }
    }

    /** Gắn cờ giữ display sống khi màn hình điện thoại tắt. */
    public static void installDisplayFlags(ClassLoader classLoader) {
        try {
            Class<?> deviceInfo = XposedHelpers.findClass(
                    "com.android.server.display.DisplayDeviceInfo", classLoader);
            int required = XposedHelpers.getStaticIntField(deviceInfo, "FLAG_OWN_DISPLAY_GROUP")
                    | XposedHelpers.getStaticIntField(deviceInfo, "FLAG_ALWAYS_UNLOCKED");

            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass(
                            "com.android.server.display.VirtualDisplayAdapter$VirtualDisplayDevice",
                            classLoader),
                    "getDisplayDeviceInfoLocked",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable() || param.getResult() == null) {
                                return;
                            }
                            Object info = param.getResult();
                            try {
                                String owner = (String) XposedHelpers.getObjectField(info, "ownerPackageName");
                                String name = (String) XposedHelpers.getObjectField(info, "name");
                                int type = XposedHelpers.getIntField(info, "type");
                                int ownerUid = XposedHelpers.getIntField(info, "ownerUid");
                                if (!AACastDisplay.matches(owner, name, type, ownerUid)) {
                                    return;
                                }
                                int flags = XposedHelpers.getIntField(info, "flags");
                                if ((required & flags) == required) {
                                    return;
                                }
                                XposedHelpers.setIntField(info, "flags", flags | required);
                                XposedBridge.log(TAG_DISPLAY + " Independent display: " + name);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG_DISPLAY + " Display unchanged: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG_DISPLAY + " Power hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG_DISPLAY + " Power hook unavailable: " + t);
        }
    }

    /** Bật dispatch input cho display ảo khi màn hình không tương tác. */
    public static void installInputPolicy(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass("com.android.server.policy.PhoneWindowManager", classLoader),
                    "shouldDispatchInputWhenNonInteractive", int.class, int.class,
                    new XC_MethodHook() {
                        private boolean logged;

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable() || Boolean.TRUE.equals(param.getResult())) {
                                return;
                            }
                            int displayId = (Integer) param.args[0];
                            if (displayId <= 0) {
                                return;
                            }
                            try {
                                android.hardware.display.DisplayManager displayManager =
                                        (android.hardware.display.DisplayManager) XposedHelpers
                                                .getObjectField(param.thisObject, "mDisplayManager");
                                Display display = displayManager == null ? null : displayManager.getDisplay(displayId);
                                if (display == null || display.getState() != Display.STATE_ON) {
                                    return;
                                }
                                if (!AACastDisplay.matches(DisplayReflect.ownerPackage(display),
                                        display.getName(), DisplayReflect.type(display),
                                        DisplayReflect.ownerUid(display))) {
                                    return;
                                }
                                param.setResult(Boolean.TRUE);
                                if (!logged) {
                                    logged = true;
                                    XposedBridge.log(TAG_DISPLAY + " Screen-off input allowed: " + displayId);
                                }
                            } catch (Throwable t) {
                                if (!logged) {
                                    logged = true;
                                    XposedBridge.log(TAG_DISPLAY + " Input policy unchanged: " + t);
                                }
                            }
                        }
                    });
            XposedBridge.log(TAG_DISPLAY + " Input policy hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG_DISPLAY + " Input policy unavailable: " + t);
        }
    }
}
