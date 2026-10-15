package com.carassistant.v10;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
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
 * by V10ProjectionSession and reconstructs the original MotionEvent stream.
 */
public final class RootMultiTouchInjector {
    private static final String TAG = "CarAssistantPinchInjector";
    private static final long IDLE_TIMEOUT_MS = 120_000L;

    private static File eventDirectory;
    private static File statusFile;
    private static File pidFile;
    private static Object inputManager;
    private static Method injectMethod;


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
                        file.delete();
                        if (line == null || line.trim().isEmpty()) continue;
                        try {
                            handle(line.trim());
                            lastActivity = SystemClock.uptimeMillis();
                        } catch (Throwable t) {
                            Log.e(TAG, "Event failed: " + line, t);
                        }
                    }
                } else {
                    if (SystemClock.uptimeMillis() - lastActivity > IDLE_TIMEOUT_MS) {
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
        String[] p = line.split("\\|", -1);
        if (p.length < 14 || !"EV".equals(p[0])) {
            throw new IllegalArgumentException("Invalid real-touch event record");
        }
        int displayId = Integer.parseInt(p[1]);
        long eventDownTime = Long.parseLong(p[2]);
        long eventTime = Long.parseLong(p[3]);
        int action = Integer.parseInt(p[4]);
        int metaState = Integer.parseInt(p[5]);
        int buttonState = Integer.parseInt(p[6]);
        float xPrecision = Float.parseFloat(p[7]);
        float yPrecision = Float.parseFloat(p[8]);
        int deviceId = Integer.parseInt(p[9]);
        int edgeFlags = Integer.parseInt(p[10]);
        int source = Integer.parseInt(p[11]);
        int flags = Integer.parseInt(p[12]);
        int count = Integer.parseInt(p[13]);
        if (count < 1 || count > 10 || p.length != 14 + count) {
            throw new IllegalArgumentException("Invalid pointer count");
        }

        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int n = 0; n < count; n++) {
            String[] fields = p[14 + n].split(",", -1);
            if (fields.length != 11) {
                throw new IllegalArgumentException("Invalid pointer data");
            }
            properties[n] = new MotionEvent.PointerProperties();
            properties[n].id = Integer.parseInt(fields[0]);
            properties[n].toolType = Integer.parseInt(fields[1]);
            coords[n] = new MotionEvent.PointerCoords();
            coords[n].x = Float.parseFloat(fields[2]);
            coords[n].y = Float.parseFloat(fields[3]);
            coords[n].pressure = Float.parseFloat(fields[4]);
            coords[n].size = Float.parseFloat(fields[5]);
            coords[n].touchMajor = Float.parseFloat(fields[6]);
            coords[n].touchMinor = Float.parseFloat(fields[7]);
            coords[n].toolMajor = Float.parseFloat(fields[8]);
            coords[n].toolMinor = Float.parseFloat(fields[9]);
            coords[n].orientation = Float.parseFloat(fields[10]);
        }

        MotionEvent event = MotionEvent.obtain(eventDownTime, eventTime, action,
                count, properties, coords, metaState, buttonState,
                xPrecision, yPrecision, deviceId, edgeFlags, source, flags,
                displayId, 0);
        try {
            Object result = injectMethod.invoke(inputManager, event, 0 /* ASYNC */);
            if (result instanceof Boolean && !((Boolean) result)) {
                throw new IllegalStateException("InputManager rejected real touch event");
            }
        } finally {
            event.recycle();
        }
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
