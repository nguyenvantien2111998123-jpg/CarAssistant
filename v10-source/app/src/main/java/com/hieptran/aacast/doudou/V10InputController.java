package com.carassistant.v10;

import java.util.Locale;

/**
 * Sends input to the currently projected virtual display.
 *
 * The actual system-side injection bridge is InputHooks.
 */
public final class V10InputController {

    private final RootShellSession shell;

    public V10InputController(RootShellSession shell) {
        this.shell = shell;
    }

    public void back(int displayId) {
        if (displayId <= 0) {
            return;
        }

        shell.run(
                15,
                "/system/bin/input -d "
                        + displayId
                        + " keyevent 4");
    }

    public void tap(int displayId, float x, float y) {
        if (displayId <= 0) {
            return;
        }

        shell.run(
                15,
                "/system/bin/input touchscreen -d "
                        + displayId
                        + " tap "
                        + number(x)
                        + " "
                        + number(y));
    }

    public void swipe(
            int displayId,
            float x1,
            float y1,
            float x2,
            float y2,
            long durationMs) {

        if (displayId <= 0) {
            return;
        }

        long duration = Math.max(1L, Math.min(10000L, durationMs));

        shell.run(
                15,
                "/system/bin/input touchscreen -d "
                        + displayId
                        + " swipe "
                        + number(x1)
                        + " "
                        + number(y1)
                        + " "
                        + number(x2)
                        + " "
                        + number(y2)
                        + " "
                        + duration);
    }

    private static String number(float value) {
        return String.format(Locale.US, "%.2f", value);
    }
}
