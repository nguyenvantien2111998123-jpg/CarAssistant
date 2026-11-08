package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts WYN from the confirmed Android Auto connection signal.
 *
 * WYN owns location, warning selection and speech. CarAsistant only starts its
 * existing task and later backgrounds it; it never force-stops WYN or creates
 * a parallel warning engine.
 */
public final class WynLifecycleController {
    private static final String TAG = "CarAsstWynLifecycle";
    private static final String WYN_PACKAGE = "com.wynauto.wyn";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "CarAsst-WYN-lifecycle");
        thread.setDaemon(true);
        return thread;
    });

    private WynLifecycleController() { }

    public static void onAndroidAutoConnected(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> launchIfNeeded(app));
    }

    private static void launchIfNeeded(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(WYN_PACKAGE);
            if (launch == null || launch.getComponent() == null) {
                Log.w(TAG, "WYN is not installed or has no launcher Activity");
                return;
            }

            ComponentName component = launch.getComponent();
            int userId = Process.myUid() / 100000;
            String flattened = component.flattenToString().replace("'", "'\\''");
            String command = "if ! pidof " + WYN_PACKAGE
                    + " >/dev/null 2>&1; then am start --user " + userId
                    + " -n '" + flattened + "'; else echo WYN_ALREADY_RUNNING; fi";

            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append(' ');
            }
            int exit = process.waitFor();
            if (exit == 0) {
                Log.i(TAG, "WYN launch request accepted: " + output);
                // The AccessibilityService waits before sending HOME, so WYN can finish
                // its first homepage initialisation and keep the same task alive.
                MediaPlaybackAccessibilityService.requestBackgroundWynWhenReady();
            } else {
                Log.w(TAG, "WYN launch command failed with exit=" + exit + ": " + output);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "WYN launch was interrupted", error);
        } catch (Throwable error) {
            Log.w(TAG, "Could not launch WYN after Android Auto connection", error);
        }
    }
}
