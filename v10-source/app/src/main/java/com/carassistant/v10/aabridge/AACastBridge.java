package com.carassistant.v10.aabridge;

import android.app.Application;
import android.app.Instrumentation;
import android.os.Build;
import android.util.Log;

import com.carassistant.v10.AACastDisplay;
import com.carassistant.v10.hook.DisplayHooks;
import com.carassistant.v10.hook.GearheadAllow;
import com.carassistant.v10.hook.GearheadDockPatcher;
import com.carassistant.v10.hook.GearheadMediaPopup;
import com.carassistant.v10.hook.GearheadAutoOpen;
import com.carassistant.v10.hook.InputHooks;
import com.carassistant.v10.hook.OrientationHooks;
import com.carassistant.v10.hook.SystemMediaKeyHooks;
import com.carassistant.v10.hook.InstallerSpoof;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Entry point của module Xposed — được khai báo trong assets/xposed_init.
 *
 * Scope cần bật trong LSPosed:
 *   • android                                  (system_server)
 *   • com.google.android.projection.gearhead   (Android Auto)
 */
public final class AACastBridge implements IXposedHookLoadPackage {

    private static final String SELF = AACastDisplay.PACKAGE;
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final String GEARHEAD_PROJECTION = GEARHEAD + ":projection";
    private static final String PLAY = "com.android.vending";

    /**
     * AADisplay AndroidAuoHook equivalent for the Gearhead projection process.
     * Install UI hooks at callApplicationOnCreate, before Gearhead inflates its UI,
     * and unhook this bootstrap after the first Application instance.
     */
    private static void installGearheadProjectionUiHook(
            ClassLoader classLoader, String processName) {
        final XC_MethodHook.Unhook[] bootstrap = new XC_MethodHook.Unhook[1];
        try {
            bootstrap[0] = XposedHelpers.findAndHookMethod(
                    Instrumentation.class,
                    "callApplicationOnCreate",
                    Application.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (bootstrap[0] != null) {
                                bootstrap[0].unhook();
                            }
                            try {
                                XposedBridge.log("[AACastBridge] projection UI bootstrap fired process="
                                        + processName + " application="
                                        + param.args[0].getClass().getName());
                                // Mirror AADisplay's lifecycle: install projection UI hooks
                                // synchronously in callApplicationOnCreate, before Gearhead
                                // inflates its connected-car UI. Keep each hook isolated so one
                                // unsupported AA method cannot prevent the other hook from loading.
                                try {
                                    GearheadAutoOpen.install(classLoader);
                                    XposedBridge.log("[AACastBridge] GearheadAutoOpen installed from "
                                            + "Instrumentation.callApplicationOnCreate");
                                } catch (Throwable error) {
                                    XposedBridge.log("[AACastBridge] GearheadAutoOpen install failed: "
                                            + Log.getStackTraceString(error));
                                }
                                // Dock and media-card interaction hooks are installed in :car,
                                // the process that owns the car-facing Gearhead UI.
                            } catch (Throwable error) {
                                XposedBridge.log("[AACastBridge] projection UI hook installation failed: "
                                        + Log.getStackTraceString(error));
                            }
                        }
                    });
            // The hook is installed synchronously in this process and fires once.
            // Store the unhook handle for removal inside the callback.
            XposedBridge.log("[AACastBridge] registered projection UI bootstrap process="
                    + processName + " loader=" + String.valueOf(classLoader));
        } catch (Throwable error) {
            XposedBridge.log("[AACastBridge] cannot register projection UI bootstrap: "
                    + Log.getStackTraceString(error));
        }
    }

    /**
     * The car-facing Gearhead UI is hosted in the dedicated :car process on
     * supported Android Auto builds. Install only the draggable popup there;
     * the :projection process is phone/projection plumbing and is not the target
     * surface for a car-screen overlay.
     */
    private static void installGearheadCarUiHook(
            ClassLoader classLoader, String processName) {
        final XC_MethodHook.Unhook[] bootstrap = new XC_MethodHook.Unhook[1];
        try {
            bootstrap[0] = XposedHelpers.findAndHookMethod(
                    Instrumentation.class,
                    "callApplicationOnCreate",
                    Application.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (bootstrap[0] != null) bootstrap[0].unhook();
                            try {
                                XposedBridge.log("[AACastBridge] car UI bootstrap fired process="
                                        + processName + " application="
                                        + param.args[0].getClass().getName());
                                GearheadMediaPopup.install();
                                try {
                                    GearheadDockPatcher.install();
                                    XposedBridge.log("[AACastBridge] GearheadDockPatcher installed in car UI process");
                                } catch (Throwable error) {
                                    XposedBridge.log("[AACastBridge] GearheadDockPatcher install failed in car process: "
                                            + Log.getStackTraceString(error));
                                }
                            } catch (Throwable error) {
                                XposedBridge.log("[AACastBridge] GearheadMediaPopup install failed: "
                                        + Log.getStackTraceString(error));
                            }
                        }
                    });
            XposedBridge.log("[AACastBridge] registered car UI popup bootstrap process="
                    + processName);
        } catch (Throwable error) {
            XposedBridge.log("[AACastBridge] cannot register car UI popup bootstrap: "
                    + Log.getStackTraceString(error));
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        String packageName = param.packageName;

        // Earliest module-entry trace: proves LSPosed reached this package/process
        // even if a later hook installation throws or diagnostic filters omit other tags.
        XposedBridge.log("[AACastBridge] handleLoadPackage package=" + packageName
                + " process=" + param.processName
                + " loader=" + String.valueOf(param.classLoader));

        // ---- system_server ----
        if ("android".equals(packageName)) {
            DisplayHooks.installDisplayFlags(param.classLoader);
            DisplayHooks.installInputPolicy(param.classLoader);
            OrientationHooks.install(param.classLoader);
            InputHooks.install(param.classLoader);
            // Intercept steering-wheel NEXT/PREVIOUS before MediaSession dispatch
            // so double-Next can invoke V10 voice without skipping a track first.
            SystemMediaKeyHooks.install(param.classLoader);
            return;
        }

        // ---- Android Auto (và chính app, để mọi code path nhất quán) ----
        if (GEARHEAD.equals(packageName) || SELF.equals(packageName)) {
            InstallerSpoof.hookLegacy(param.classLoader, PLAY);

            if (GEARHEAD.equals(packageName)) {
                XposedBridge.log("[AACastBridge] installing GearheadAllow process="
                        + param.processName);
                try {
                    GearheadAllow.install(param.classLoader);
                    XposedBridge.log("[AACastBridge] GearheadAllow.install returned");
                } catch (Throwable t) {
                    XposedBridge.log("[AACastBridge] GearheadAllow.install FAILED: " + t);
                }
                // Match AADisplay's Gearhead UI lifecycle: only the :projection
                // process owns the projected UI hook, and hook installation happens
                // immediately before Application.onCreate via Instrumentation.
                if (GEARHEAD_PROJECTION.equals(param.processName)) {
                    installGearheadProjectionUiHook(param.classLoader, param.processName);
                    // GearheadAutoOpen and dock/media metadata hooks remain scoped to
                    // the existing projection process to avoid changing other paths.
                } else if ((GEARHEAD + ":car").equals(param.processName)) {
                    // Attach the free-position media popup to the car-facing Gearhead
                    // view tree, not the phone-side projection UI.
                    installGearheadCarUiHook(param.classLoader, param.processName);
                }

                // No experimental Gearhead auto-open or steering/media-key interception.
                // Android Auto projection and display/input hooks remain enabled.
            }

            if (Build.VERSION.SDK_INT >= 30) {
                InstallerSpoof.hookInstallSource("getInitiatingPackageName", PLAY);
                InstallerSpoof.hookInstallSource("getInstallingPackageName", PLAY);
                if (Build.VERSION.SDK_INT >= 33) {
                    InstallerSpoof.hookInstallSource("getPackageSource", 2);
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    InstallerSpoof.hookInstallSource("getUpdateOwnerPackageName", PLAY);
                }
            }
            XposedBridge.log("[AACast] Module loaded in " + packageName
                    + " process=" + param.processName);
        }
    }
}
