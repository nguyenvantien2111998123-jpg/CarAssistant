package com.carassistant.v10.hook;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Detects the AA projection edge and requests the same Gearhead Car Activity hand-off used by Gemini. */
public final class GearheadAutoOpen {
    private static final String TAG = "AACastAutoOpen";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final String SELF = "com.carassistant.v10";
    private static final long WYN_START_DELAY_MS = 25000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Context app;
    private static DisplayManager displayManager;
    private static boolean connected, carRequested, installed, wynRequested, wynStarting;
    private static int carRetries, wynRetries;
    private static String lastFacetState = "";

    private static final Runnable wynRetry = new Runnable() {
        @Override public void run() {
            if (!connected || wynRequested || wynStarting || app == null) return;
            if (startWyn()) {
                wynStarting = true;
                Log.i(TAG, "WYN ordered startup request sent to CarAsistant root receiver");
            } else if (++wynRetries < 8) MAIN.postDelayed(this, 2500L);
            else Log.e(TAG, "WYN startup retries exhausted for this AA connection");
        }
    };

    private static final Runnable carRetry = new Runnable() {
        @Override public void run() {
            if (!connected || carRequested || app == null) return;
            if (launchCarActivity()) {
                carRequested = true;
                Log.i(TAG, "Car Activity launch hand-off invoked; awaiting actual CarAsistant activity evidence");
            } else if (++carRetries < 8) MAIN.postDelayed(this, 1500L);
            else Log.e(TAG, "CarAsistant Car Activity launch retries exhausted; inspect controller trace");
        }
    };

    private GearheadAutoOpen() { }

    public static synchronized void install() {
        if (installed) { Log.i(TAG, "install requested again; already installed"); return; }
        installed = true;
        Log.i(TAG, "INSTALL_REQUEST process=" + Application.getProcessName());
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            if (context != null && GEARHEAD.equals(context.getPackageName())) {
                                Context application = context.getApplicationContext();
                                MAIN.post(() -> attach(application != null ? application : context));
                            }
                        }
                    });
            Log.i(TAG, "Application.attach hook installed; waiting for Gearhead context");
        } catch (Throwable error) {
            installed = false;
            Log.e(TAG, "Application.attach hook installation failed", error);
        }
    }

    private static void attach(Context context) {
        if (app != null) return;
        app = context;
        Log.i(TAG, "GEARHEAD_ATTACHED package=" + context.getPackageName()
                + " process=" + Application.getProcessName()
                + " classLoader=" + context.getClassLoader().getClass().getName());
        displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) { Log.e(TAG, "DisplayManager unavailable in Gearhead process"); return; }
        displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { scanDisplays(); }
            @Override public void onDisplayChanged(int id) { scanDisplays(); }
            @Override public void onDisplayRemoved(int id) { scanDisplays(); }
        }, MAIN);
        scanDisplays();
    }

    private static void scanDisplays() {
        if (displayManager == null) return;
        boolean found = false;
        StringBuilder state = new StringBuilder();
        for (Display display : displayManager.getDisplays()) {
            if (display == null) continue;
            String name = display.getName() == null ? "" : display.getName();
            if (name.contains("GhFacetBar")) {
                found = display.getState() == Display.STATE_ON;
                state.append("id=").append(display.getDisplayId()).append(",name=").append(name)
                        .append(",state=").append(display.getState()).append(';');
            }
        }
        String snapshot = state.toString();
        if (!snapshot.equals(lastFacetState)) {
            lastFacetState = snapshot;
            Log.i(TAG, "GH_FACET_SCAN " + (snapshot.isEmpty() ? "GhFacetBar absent" : snapshot));
        }
        if (found && !connected) {
            connected = true; carRequested = false; wynRequested = false; wynStarting = false;
            carRetries = 0; wynRetries = 0;
            MAIN.removeCallbacks(carRetry); MAIN.removeCallbacks(wynRetry);
            Log.i(TAG, "ANDROID_AUTO_CONNECTED_EDGE; scheduling Car Activity and independent WYN startup");
            MAIN.postDelayed(carRetry, 1800L);
            MAIN.postDelayed(wynRetry, WYN_START_DELAY_MS);
        } else if (!found && connected) {
            connected = false; carRequested = false; wynRequested = false; wynStarting = false;
            carRetries = 0; wynRetries = 0;
            MAIN.removeCallbacks(carRetry); MAIN.removeCallbacks(wynRetry);
            Log.i(TAG, "ANDROID_AUTO_DISCONNECTED_EDGE");
        }
    }

    private static boolean startWyn() {
        try {
            Intent marker = new Intent("com.carassistant.v10.ACTION_AUTO_BACKGROUND_WYN");
            marker.setComponent(new ComponentName(SELF, SELF + ".WynAutoStartReceiver"));
            marker.setPackage(SELF);
            app.sendOrderedBroadcast(marker, null, new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    wynStarting = false;
                    if (!connected) return;
                    if (getResultCode() == Activity.RESULT_OK) {
                        wynRequested = true;
                        Log.i(TAG, "WYN root launch confirmed by CarAsistant receiver");
                    } else if (++wynRetries < 8) {
                        Log.w(TAG, "WYN root launch not confirmed; retry=" + wynRetries);
                        MAIN.postDelayed(wynRetry, 2500L);
                    } else Log.e(TAG, "WYN root launch failed after retries");
                }
            }, MAIN, Activity.RESULT_CANCELED, null, null);
            return true;
        } catch (Throwable error) {
            wynStarting = false;
            Log.w(TAG, "WYN startup broadcast failed", error);
            return false;
        }
    }

    private static boolean launchCarActivity() {
        try {
            ClassLoader loader = app.getClassLoader();
            Class<?> controller = XposedHelpers.findClassIfExists(
                    "com.google.android.projection.gearhead.service.CarSystemUiControllerService", loader);
            if (controller == null) {
                ClassLoader threadLoader = Thread.currentThread().getContextClassLoader();
                if (threadLoader != null && threadLoader != loader) {
                    controller = XposedHelpers.findClassIfExists(
                            "com.google.android.projection.gearhead.service.CarSystemUiControllerService",
                            threadLoader);
                }
            }
            if (controller == null) {
                Log.w(TAG, "CAR_CONTROLLER_CLASS_NOT_FOUND loader=" + loader.getClass().getName()
                        + "; process=" + Application.getProcessName());
                return false;
            }
            Method target = null;
            List<String> candidates = new ArrayList<>();
            for (Class<?> type = controller; type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getParameterTypes().length != 1
                            || method.getParameterTypes()[0] != Intent.class) continue;
                    candidates.add(Modifier.toString(method.getModifiers()) + " "
                            + type.getName() + "#" + method.getName() + "(Intent)");
                    if (Modifier.isStatic(method.getModifiers()) && "a".equals(method.getName())) target = method;
                }
            }
            if (target == null) {
                Log.w(TAG, "CAR_CONTROLLER_METHOD_NOT_FOUND intentMethods=" + candidates);
                return false;
            }
            Intent request = new Intent();
            request.setComponent(new ComponentName(SELF, SELF + ".AACastCarService"));
            request.putExtra("android.intent.extra.PACKAGE_NAME", SELF);
            request.putExtra("com.carassistant.v10.AUTO_OPEN_ON_AA", true);
            target.setAccessible(true);
            Log.i(TAG, "CAR_CONTROLLER_INVOKE method=" + target.toGenericString()
                    + " target=" + request.getComponent());
            target.invoke(null, request);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Car Activity hand-off failed", error);
            return false;
        }
    }
}