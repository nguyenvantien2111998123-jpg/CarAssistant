package com.carassistant.v10.aabridge;

import android.app.Application;
import android.app.Instrumentation;
import android.os.Build;
import android.util.Log;

import com.carassistant.v10.AACastDisplay;
import com.carassistant.v10.hook.DisplayHooks;
import com.carassistant.v10.hook.GearheadAllow;
import com.carassistant.v10.hook.GearheadDockPatcher;
import com.carassistant.v10.hook.InputHooks;
import com.carassistant.v10.hook.OrientationHooks;
import com.carassistant.v10.hook.InstallerSpoof;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
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
            XposedHelpers.findAndHookMethod(
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
                                GearheadDockPatcher.install();
                                XposedBridge.log("[AACastBridge] GearheadDockPatcher installed from "
                                        + "Instrumentation.callApplicationOnCreate");
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
