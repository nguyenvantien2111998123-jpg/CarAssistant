package com.carassistant.v10.hook;

import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.view.Display;
import android.view.InputEvent;
import android.view.MotionEvent;

import com.carassistant.v10.V10Display;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Routes root-injected MotionEvents to CarAssistant's secondary display.
 * Hook both Binder entry points because InputManagerGlobal.injectInputEvent()
 * normally calls injectInputEvent(), not necessarily injectInputEventToTarget().
 */
public final class InputHooks {
    private static final String TAG_INPUT = "[V10 Input]";
    private static final int INJECT_TIMEOUT = 0x1F40;
    private static final int POLICY_FLAGS = 0x44000000;

    private InputHooks() { }

    public static void install(ClassLoader classLoader) {
        Class<?> service;
        try {
            service = XposedHelpers.findClass(
                    "com.android.server.input.InputManagerService", classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG_INPUT + " InputManagerService unavailable: " + t);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(service, "injectInputEvent",
                    InputEvent.class, int.class, new XC_MethodHook() {
                        private boolean loggedMultiTouch;
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            handle(param, (Integer) param.args[1], -1, this);
                        }
                    });
            XposedBridge.log(TAG_INPUT + " injectInputEvent hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG_INPUT + " injectInputEvent hook unavailable: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(service, "injectInputEventToTarget",
                    InputEvent.class, int.class, int.class, new XC_MethodHook() {
                        private boolean loggedMultiTouch;
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            handle(param, (Integer) param.args[1],
                                    (Integer) param.args[2], this);
                        }
                    });
            XposedBridge.log(TAG_INPUT + " injectInputEventToTarget hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG_INPUT + " injectInputEventToTarget hook unavailable: " + t);
        }
    }

    private static void handle(XC_MethodHook.MethodHookParam param, int mode,
            int targetUid, XC_MethodHook hook) {
        if (!(param.args[0] instanceof MotionEvent)
                || Binder.getCallingUid() != 0
                || mode < 0 || mode > 2
                || targetUid != -1) {
            return;
        }

        MotionEvent event = (MotionEvent) param.args[0];
        int displayId;
        try {
            displayId = (Integer) XposedHelpers.callMethod(event, "getDisplayId");
        } catch (Throwable t) {
            XposedBridge.log(TAG_INPUT + " Cannot read event display: " + t);
            return;
        }
        if (displayId <= 0) return;

        try {
            android.content.Context context = (android.content.Context)
                    XposedHelpers.getObjectField(param.thisObject, "mContext");
            DisplayManager manager = (DisplayManager)
                    context.getSystemService(android.content.Context.DISPLAY_SERVICE);
            Display display = manager == null ? null : manager.getDisplay(displayId);
            if (display == null || display.getState() != Display.STATE_ON
                    || !V10Display.matches(DisplayReflect.ownerPackage(display),
                    display.getName(), DisplayReflect.type(display),
                    DisplayReflect.ownerUid(display))) {
                return;
            }

            Object nativeInputManager =
                    XposedHelpers.getObjectField(param.thisObject, "mNative");
            MotionEvent copy = cloneWithDisplay(event, displayId);
            long token = Binder.clearCallingIdentity();
            try {
                int result = (Integer) XposedHelpers.callMethod(nativeInputManager,
                        "injectInputEvent", copy, Boolean.FALSE, -1, mode,
                        INJECT_TIMEOUT, POLICY_FLAGS);
                param.setResult(result == 0);
                if (result != 0) {
                    XposedBridge.log(TAG_INPUT + " Native injection rejected: display="
                            + displayId + " result=" + result);
                }
                if (result == 0 && event.getPointerCount() >= 2) {
                    XposedBridge.log(TAG_INPUT + " multi-pointer accepted: display="
                            + displayId + " action=" + event.getAction()
                            + " pointers=" + event.getPointerCount());
                }
            } finally {
                Binder.restoreCallingIdentity(token);
                copy.recycle();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG_INPUT + " Native injection hook failed; original path retained: " + t);
        }
    }

    private static MotionEvent cloneWithDisplay(MotionEvent source, int displayId) {
        int count = source.getPointerCount();
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            coords[i] = new MotionEvent.PointerCoords();
            source.getPointerProperties(i, properties[i]);
            source.getPointerCoords(i, coords[i]);
        }
        MotionEvent copy = MotionEvent.obtain(source.getDownTime(), source.getEventTime(),
                source.getAction(), count, properties, coords, source.getMetaState(),
                source.getButtonState(), source.getXPrecision(), source.getYPrecision(),
                -1, source.getEdgeFlags(), source.getSource(), source.getFlags());
        XposedHelpers.callMethod(copy, "setDisplayId", displayId);
        return copy;
    }
}