package com.carassistant.v10.hook;

import com.carassistant.v10.AACastDisplay;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook cổng kiểm tra của gearhead: mọi method boolean nhận 1 package String
 * trong class `iwt` sẽ trả true cho package của app.
 *
 * LƯU Ý: tên class/method có thể đổi theo phiên bản Android Auto. Hook fail-open:
 * nếu không tìm thấy thì bỏ qua, không làm hỏng host.
 */
public final class GearheadAllow {

    private static final String TAG = "[AACast Dock]";
    private static final String[] CANDIDATE_CLASSES = {"iwt"};

    private GearheadAllow() {
    }

    public static void install(ClassLoader classLoader) {
        for (String className : CANDIDATE_CLASSES) {
            try {
                Class<?> target = XposedHelpers.findClassIfExists(className, classLoader);
                if (target == null) {
                    continue;
                }
                int hooked = XposedBridge.hookAllMethods(target, "z", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0
                                && AACastDisplay.PACKAGE.equals(param.args[0])
                                && (param.method instanceof Method)
                                && ((Method) param.method).getReturnType() == boolean.class) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                }).size();
                XposedBridge.log(TAG + " Gearhead allow hook installed on " + className
                        + "." + "z (" + hooked + " methods)");
                return;
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Allow hook failed for " + className + ": " + t);
            }
        }
        XposedBridge.log(TAG + " Allow hook unavailable (class not found). "
                + "App may still work if installed from Play Store or as a system app.");
    }
}
