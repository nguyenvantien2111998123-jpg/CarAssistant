package com.carassistant.v10;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Root-only helper launched with app_process. It reads atomic event files written
 * by V10ProjectionSession and injects genuine multi-pointer MotionEvents.
 */
public final class RootMultiTouchInjector {
    private static final String TAG = "CarAssistantPinchInjector";
    private static final long IDLE_TIMEOUT_MS = 120_000L;
    private static final int INJECT_WAIT_FOR_FINISH = 2;

    private static File eventDirectory;
    private static File statusFile;
    private static File pidFile;
    private static Object inputManager;
    private static Method injectMethod;

    private static boolean active;
    private static int activeDisplayId = -1;
    private static long downTime;

    private RootMultiTouchInjector() { }

    public static void main(String[] args) {
        if (args == null || args.length < 3) return;
        eventDirectory = new File(args[0]);
        statusFile = new File(args[1]);
        pidFile = new File(args[2]);

        try {
            eventDirectory.mkdirs();
            initializeInputManager();
            writeFile(pidFile, Integer.toString(Process.myPid()));
            writeFile(statusFile, "READY");
            Log.i(TAG, "Root multi-touch injector ready pid=" + Process.myPid());

            long lastActivity = SystemClock.uptimeMillis();
            while (true) {
                File[] events = eventDirectory.listFiles(
                        (dir, name) -> name.startsWith("evt_") && !name.endsWith(".tmp"));
                if (events != null && events.length > 0) {
                    Arrays.sort(events, Comparator.comparing(File::getName));
                    for (File file : events) {
                        String line = readLine(file);
                        if (!file.delete()) file.deleteOnExit();
                        if (line == null || line.trim().isEmpty()) continue;
                        try {
                            handle(line.trim());
                            lastActivity = SystemClock.uptimeMillis();
                        } catch (Throwable t) {
                            Log.e(TAG, "Event failed: " + line, t);
                            cancelActiveGesture();
                        }
                    }
                } else {
                    if (!active && SystemClock.uptimeMillis() - lastActivity > IDLE_TIMEOUT_MS) {
                        break;
                    }
                    Thread.sleep(4L);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Injector stopped", t);
            try {
                writeFile(statusFile, "ERROR:" + t.getClass().getSimpleName());
            } catch (Throwable ignored) { }
        } finally {
            cancelActiveGesture();
            removeIfOwned(pidFile);
            removeIfOwned(statusFile);
        }
    }

    private static void initializeInputManager() throws Exception {
        Class<?> globalClass = Class.forName("android.hardware.input.InputManagerGlobal");
        Method getInstance = globalClass.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        inputManager = getInstance.invoke(null);
        injectMethod = globalClass.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
        injectMethod.setAccessible(true);
    }

    private static void handle(String line) throws Exception {
        String[] p = line.split("\\|");
        if (p.length != 6) throw new IllegalArgumentException("Invalid event record");
        String action = p[0];
        int displayId = Integer.parseInt(p[1]);
        float x0 = Float.parseFloat(p[2]);
        float y0 = Float.parseFloat(p[3]);
        float x1 = Float.parseFloat(p[4]);
        float y1 = Float.parseFloat(p[5]);

        if ("START".equals(action)) {
            cancelActiveGesture();
            activeDisplayId = displayId;
            downTime = SystemClock.uptimeMillis();
            injectOne(MotionEvent.ACTION_DOWN, x0, y0);
            active = true;
            injectTwo(MotionEvent.ACTION_POINTER_DOWN | (1 << 8), x0, y0, x1, y1);
        } else if ("MOVE".equals(action)) {
            if (active && displayId == activeDisplayId) {
                injectTwo(MotionEvent.ACTION_MOVE, x0, y0, x1, y1);
            }
        } else if ("END".equals(action)) {
            if (active && displayId == activeDisplayId) {
                injectTwo(MotionEvent.ACTION_POINTER_UP | (1 << 8), x0, y0, x1, y1);
                injectOne(MotionEvent.ACTION_UP, x0, y0);
                active = false;
                activeDisplayId = -1;
            }
        } else if ("CANCEL".equals(action)) {
            if (active && displayId == activeDisplayId) {
                injectTwo(MotionEvent.ACTION_CANCEL, x0, y0, x1, y1);
                active = false;
                activeDisplayId = -1;
            }
        } else {
            throw new IllegalArgumentException("Unknown action " + action);
        }
    }

    private static void injectOne(int action, float x, float y) throws Exception {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        properties[0] = pointerProperties(0);
        coords[0] = pointerCoords(x, y);
        inject(createEvent(action, 1, properties, coords));
    }

    private static void injectTwo(int action, float x0, float y0, float x1, float y1)
            throws Exception {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        properties[0] = pointerProperties(0);
        properties[1] = pointerProperties(1);
        coords[0] = pointerCoords(x0, y0);
        coords[1] = pointerCoords(x1, y1);
        inject(createEvent(action, 2, properties, coords));
    }

    private static MotionEvent.PointerProperties pointerProperties(int id) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = id;
        properties.toolType = MotionEvent.TOOL_TYPE_FINGER;
        return properties;
    }

    private static MotionEvent.PointerCoords pointerCoords(float x, float y) {
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = 1.0f;
        coords.size = 1.0f;
        return coords;
    }

    private static MotionEvent createEvent(int action, int count,
            MotionEvent.PointerProperties[] properties, MotionEvent.PointerCoords[] coords) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(downTime, now, action, count, properties, coords,
                0, 0, 1.0f, 1.0f, -1, 0, InputDevice.SOURCE_TOUCHSCREEN,
                activeDisplayId, 0);
    }

    private static void inject(MotionEvent event) throws Exception {
        try {
            Object result = injectMethod.invoke(inputManager, event, INJECT_WAIT_FOR_FINISH);
            if (result instanceof Boolean && !((Boolean) result)) {
                throw new IllegalStateException("InputManager rejected MotionEvent");
            }
        } finally {
            event.recycle();
        }
    }

    private static void cancelActiveGesture() {
        if (!active) return;
        try {
            injectTwo(MotionEvent.ACTION_CANCEL, 0, 0, 0, 0);
        } catch (Throwable t) {
            Log.w(TAG, "Could not cancel previous gesture", t);
        }
        active = false;
        activeDisplayId = -1;
    }

    private static String readLine(File file) throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine();
        }
    }

    private static void writeFile(File file, String value) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write(value);
            writer.write('\n');
        }
    }

    private static void removeIfOwned(File file) {
        try {
            if (file != null && file.isFile()) {
                String value = readLine(file);
                if (file.equals(pidFile) && value != null
                        && !Integer.toString(Process.myPid()).equals(value.trim())) {
                    return;
                }
                file.delete();
            }
        } catch (Throwable ignored) { }
    }
}
