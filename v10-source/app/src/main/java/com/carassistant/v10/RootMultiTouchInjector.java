package com.carassistant.v10;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Root-side multi-pointer injector. Loaded from the installed APK with app_process.
 * InputHooks in system_server assigns the trusted virtual-display routing.
 */
public final class RootMultiTouchInjector {
    private static final int INJECT_ASYNC = 0;
    private static final int ACTION_POINTER_INDEX_SHIFT = 8;
    private static final int MAX_STEPS = 24;
    private static final int MIN_STEPS = 6;

    private RootMultiTouchInjector() { }

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 10) {
                throw new IllegalArgumentException("Expected displayId, 8 coordinates, duration");
            }
            int displayId = Integer.parseInt(args[0]);
            if (displayId <= 0) {
                throw new IllegalArgumentException("Refusing multi-touch injection to default display");
            }
            float x1s = parseCoordinate(args[1]);
            float y1s = parseCoordinate(args[2]);
            float x1e = parseCoordinate(args[3]);
            float y1e = parseCoordinate(args[4]);
            float x2s = parseCoordinate(args[5]);
            float y2s = parseCoordinate(args[6]);
            float x2e = parseCoordinate(args[7]);
            float y2e = parseCoordinate(args[8]);
            long duration = Math.max(80L, Math.min(800L, Long.parseLong(args[9])));

            Class<?> managerClass = Class.forName("android.hardware.input.InputManager");
            Method getInstance = managerClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object inputManager = getInstance.invoke(null);
            Method inject = managerClass.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
            inject.setAccessible(true);
            Method setDisplayId = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
            setDisplayId.setAccessible(true);

            long downTime = SystemClock.uptimeMillis();
            PointerState first = new PointerState(0, x1s, y1s);
            PointerState second = new PointerState(1, x2s, y2s);
            injectOne(inputManager, inject, setDisplayId, displayId,
                    event(downTime, downTime, MotionEvent.ACTION_DOWN, first, null));

            long pointerDownTime = SystemClock.uptimeMillis();
            injectOne(inputManager, inject, setDisplayId, displayId,
                    event(downTime, pointerDownTime,
                            MotionEvent.ACTION_POINTER_DOWN | (1 << ACTION_POINTER_INDEX_SHIFT),
                            first, second));

            int steps = (int) Math.max(MIN_STEPS, Math.min(MAX_STEPS, duration / 16L));
            long interval = Math.max(1L, duration / steps);
            for (int i = 1; i <= steps; i++) {
                float fraction = i / (float) steps;
                PointerState p1 = first.interpolate(x1e, y1e, fraction);
                PointerState p2 = second.interpolate(x2e, y2e, fraction);
                long eventTime = SystemClock.uptimeMillis();
                injectOne(inputManager, inject, setDisplayId, displayId,
                        event(downTime, eventTime, MotionEvent.ACTION_MOVE, p1, p2));
                if (i < steps) {
                    SystemClock.sleep(interval);
                }
            }

            long upSecondTime = SystemClock.uptimeMillis();
            injectOne(inputManager, inject, setDisplayId, displayId,
                    event(downTime, upSecondTime,
                            MotionEvent.ACTION_POINTER_UP | (1 << ACTION_POINTER_INDEX_SHIFT),
                            new PointerState(0, x1e, y1e), new PointerState(1, x2e, y2e)));

            long upFirstTime = SystemClock.uptimeMillis();
            injectOne(inputManager, inject, setDisplayId, displayId,
                    event(downTime, upFirstTime, MotionEvent.ACTION_UP,
                            new PointerState(0, x1e, y1e), null));

            System.out.println("MULTITOUCH_OK display=" + displayId + " steps=" + steps);
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static float parseCoordinate(String value) {
        float result = Float.parseFloat(value);
        if (Float.isNaN(result) || Float.isInfinite(result) || result < 0f) {
            throw new IllegalArgumentException("Invalid coordinate: " + value);
        }
        return result;
    }

    private static MotionEvent event(long downTime, long eventTime, int action,
                                     PointerState first, PointerState second) {
        int count = second == null ? 1 : 2;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
        fillPointer(properties, coordinates, 0, first);
        if (count == 2) {
            fillPointer(properties, coordinates, 1, second);
        }
        MotionEvent event = MotionEvent.obtain(
                downTime, eventTime, action, count, properties, coordinates,
                0, 0, 1f, 1f, -1, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return event;
    }

    private static void fillPointer(MotionEvent.PointerProperties[] properties,
                                    MotionEvent.PointerCoords[] coordinates,
                                    int index, PointerState state) {
        MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
        property.id = state.id;
        property.toolType = MotionEvent.TOOL_TYPE_FINGER;
        properties[index] = property;

        MotionEvent.PointerCoords coordinate = new MotionEvent.PointerCoords();
        coordinate.x = state.x;
        coordinate.y = state.y;
        coordinate.pressure = 1f;
        coordinate.size = 1f;
        coordinates[index] = coordinate;
    }

    private static void injectOne(Object manager, Method inject, Method setDisplayId,
                                  int displayId, MotionEvent event) throws Exception {
        try {
            setDisplayId.invoke(event, displayId);
            Object result = inject.invoke(manager, event, INJECT_ASYNC);
            if (!(result instanceof Boolean) || !((Boolean) result)) {
                throw new IllegalStateException("InputManager rejected event action=" + event.getAction());
            }
        } finally {
            event.recycle();
        }
    }

    private static final class PointerState {
        final int id;
        final float x;
        final float y;

        PointerState(int id, float x, float y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }

        PointerState interpolate(float endX, float endY, float fraction) {
            return new PointerState(id,
                    x + (endX - x) * fraction,
                    y + (endY - y) * fraction);
        }
    }
}
