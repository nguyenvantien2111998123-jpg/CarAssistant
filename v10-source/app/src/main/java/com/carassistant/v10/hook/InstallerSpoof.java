package com.carassistant.v10.hook;

import android.content.pm.InstallSourceInfo;
import android.os.Build;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Package-scoped installer-source compatibility for Android Auto. */
public final class InstallerSpoof {
    private static final String TAG = "[AACast Installer]";
    private static final String TARGET_PACKAGE = "com.carassistant.v10";

    private InstallerSpoof() { }

    public static void hookLegacy(ClassLoader classLoader, String installer) {
        hookGetInstaller("android.app.ApplicationPackageManager", classLoader, installer);
        hookGetInstaller("android.content.pm.IPackageManager$Stub$Proxy",
                classLoader, installer);
    }

    private static void hookGetInstaller(String className, ClassLoader classLoader,
                                         String installer) {
        try {
            Class<?> target = XposedHelpers.findClassIfExists(className, classLoader);
            if (target == null) {
                target = XposedHelpers.findClassIfExists(className, (ClassLoader) null);
            }
            if (target == null) {
                XposedBridge.log(TAG + " class unavailable: " + className);
                return;
            }
            int count = XposedBridge.hookAllMethods(target, "getInstallerPackageName",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length == 0
                                    || !TARGET_PACKAGE.equals(param.args[0])) return;
                            param.setResult(installer);
                        }
                    }).size();
            XposedBridge.log(TAG + " scoped getInstallerPackageName: "
                    + className + " overloads=" + count);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " legacy hook unavailable for "
                    + className + ": " + error);
        }
    }

    /**
     * Android 11+ returns an InstallSourceInfo object. Change only the object
     * returned for CarAsistant; never hook InstallSourceInfo getters globally.
     */
    public static void hookInstallSourceInfo(ClassLoader classLoader, String installer) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            Class<?> target = XposedHelpers.findClassIfExists(
                    "android.app.ApplicationPackageManager", classLoader);
            if (target == null) {
                XposedBridge.log(TAG + " ApplicationPackageManager unavailable");
                return;
            }
            int count = XposedBridge.hookAllMethods(target, "getInstallSourceInfo",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length == 0
                                    || !TARGET_PACKAGE.equals(param.args[0])
                                    || !(param.getResult() instanceof InstallSourceInfo)) return;
                            Object info = param.getResult();
                            boolean initiating = setField(info, "mInitiatingPackageName", installer);
                            boolean installing = setField(info, "mInstallingPackageName", installer);
                            if (Build.VERSION.SDK_INT >= 33) {
                                setIntField(info, "mPackageSource", 2);
                            }
                            XposedBridge.log(TAG + " scoped InstallSourceInfo: initiating="
                                    + initiating + " installing=" + installing);
                        }
                    }).size();
            XposedBridge.log(TAG + " scoped getInstallSourceInfo installed; overloads=" + count);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " InstallSourceInfo hook unavailable: " + error);
        }
    }

    private static boolean setField(Object target, String name, Object value) {
        Field field = findField(target.getClass(), name);
        if (field == null) return false;
        try {
            field.setAccessible(true);
            field.set(target, value);
            return true;
        } catch (Throwable error) {
            XposedBridge.log(TAG + " could not set " + name + ": " + error);
            return false;
        }
    }

    private static boolean setIntField(Object target, String name, int value) {
        Field field = findField(target.getClass(), name);
        if (field == null) return false;
        try {
            field.setAccessible(true);
            field.setInt(target, value);
            return true;
        } catch (Throwable error) {
            XposedBridge.log(TAG + " could not set " + name + ": " + error);
            return false;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Continue through the hierarchy.
            }
        }
        return null;
    }
}
