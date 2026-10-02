package com.carassistant.v10.hook;

import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.view.Display;
import android.view.InputEvent;
import android.view.MotionEvent;

import com.carassistant.v10.AACastDisplay;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook InputManagerService.injectInputEventToTarget trên system_server:
 * khi uid 0 (su) gửi sự kiện tới display ảo của app, clone event với displayId
 * rồi gọi lại đường native với policyFlags tin cậy — nhờ đó `su input -d <id>`
 * thực sự tới được app con.
 */
public final class InputHooks {

    private static final String TAG_INPUT = "[AACast Input]";
    private static final int INJECT_TIMEOUT = 0x1F40;        // 8000 ms
    private static final int POLICY_FLAGS = 0x44000000;

    private InputHooks() {
    }

    /** Cài hook vào InputManagerService của system_server. */
    public static void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass("com.android.server.input.InputManagerService", classLoader),
                    "injectInputEventToTarget", InputEvent.class, int.class, int.class,
                    new XC_MethodHook() {
                        private boolean logged;

                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.args[0] instanceof MotionEvent)) {
                                return;
                            }
                            int mode = (Integer) param.args[1];
                            int targetUid = (Integer) param.args[2];
                            if (Binder.getCallingUid() != 0 || mode < 0 || mode > 2 || targetUid != -1) {
                                return;
                            }
                            MotionEvent event = (MotionEvent) param.args[0];
                            int displayId = (Integer) XposedHelpers.callMethod(event, "getDisplayId");
                            if (displayId <= 0) {
                                return;
                            }
                            try {
                                android.content.Context context = (android.content.Context)
                                        XposedHelpers.getObjectField(param.thisObject, "mContext");
                                DisplayManager displayManager = (DisplayManager)
                                        context.getSystemService(android.content.Context.DISPLAY_SERVICE);
                                Display display = displayManager == null ? null : displayManager.getDisplay(displayId);
                                if (display == null || display.getState() != Display.STATE_ON
                                        || !AACastDisplay.matches(DisplayReflect.ownerPackage(display),
                                        display.getName(), DisplayReflect.type(display),
                                        DisplayReflect.ownerUid(display))) {
                                    return;
                                }
                                Object nativeInputManager = XposedHelpers.getObjectField(param.thisObject, "mNative");
                                MotionEvent copy = cloneWithDisplay(event, displayId);
                                long token = Binder.clearCallingIdentity();
                                try {
                                    int result = (Integer) XposedHelpers.callMethod(nativeInputManager,
                                            "injectInputEvent",
                                            copy, Boolean.FALSE, -1, mode, INJECT_TIMEOUT, POLICY_FLAGS);
                                    param.setResult(result == 0);
                                    if (!logged || result != 0) {
                                        logged = true;
                                        XposedBridge.log(TAG_INPUT + " display=" + displayId
                                                + " nativeResult=" + result);
                                    }
                                } finally {
                                    Binder.restoreCallingIdentity(token);
                                    copy.recycle();
                                }
                            } catch (Throwable t) {
                                if (!logged) {
                                    logged = true;
                                    XposedBridge.log(TAG_INPUT + " Original path retained: " + t);
                                }
                            }
                        }
                    });
            XposedBridge.log(TAG_INPUT + " Root pane injection hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG_INPUT + " Hook unavailable: " + t);
        }
    }

    /** Clone event và gán displayId. */
    private static MotionEvent cloneWithDisplay(MotionEvent source, int displayId) {
        int pointerCount = source.getPointerCount();
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointerCount];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            coords[i] = new MotionEvent.PointerCoords();
            source.getPointerProperties(i, properties[i]);
            source.getPointerCoords(i, coords[i]);
        }
        MotionEvent copy = MotionEvent.obtain(source.getDownTime(), source.getEventTime(),
                source.getAction(), pointerCount, properties, coords, source.getMetaState(),
                source.getButtonState(), source.getXPrecision(), source.getYPrecision(), -1,
                source.getEdgeFlags(), source.getSource(), source.getFlags());
        XposedHelpers.callMethod(copy, "setDisplayId", displayId);
        return copy;
    }
}
