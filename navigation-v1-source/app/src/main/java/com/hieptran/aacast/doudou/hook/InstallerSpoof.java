package com.carassistant.v10.hook;

import android.content.pm.InstallSourceInfo;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Spoof nguồn cài đặt thành Play Store để Android Auto chấp nhận app.
 */
public final class InstallerSpoof {

    private InstallerSpoof() {
    }

    /** Hook mọi method getInstallerPackageName trên 2 lớp API. */
    public static void hookLegacy(ClassLoader classLoader, String installer) {
        hookGetInstaller("android.app.ApplicationPackageManager", classLoader, installer);
        hookGetInstaller("android.content.pm.IPackageManager$Stub$Proxy", classLoader, installer);
    }

    private static void hookGetInstaller(String className, ClassLoader classLoader, String installer) {
        try {
            Class<?> target = XposedHelpers.findClassIfExists(className, classLoader);
            if (target == null) {
                target = XposedHelpers.findClassIfExists(className, (ClassLoader) null);
            }
            if (target == null) {
                return;
            }
            XposedBridge.hookAllMethods(target, "getInstallerPackageName", constant(installer)).isEmpty();
        } catch (Throwable t) {
            // fail-open: module chỉ đơn giản là không có tác dụng
            t.toString();
        }
    }

    /** Hook 1 method của InstallSourceInfo (API 30+). */
    public static void hookInstallSource(String methodName, Object value) {
        try {
            XposedBridge.hookAllMethods(InstallSourceInfo.class, methodName, constant(value)).isEmpty();
        } catch (Throwable t) {
            t.toString();
        }
    }

    /** Hook luôn setResult hằng số. */
    private static XC_MethodHook constant(Object value) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                param.setResult(value);
            }
        };
    }
}
