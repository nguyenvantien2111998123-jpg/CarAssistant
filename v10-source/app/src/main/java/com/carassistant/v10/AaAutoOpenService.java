package com.carassistant.v10;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import android.support.car.Car;
import android.support.car.CarConnectionCallback;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Starts the Android Auto projection entry through the supported-by-AA legacy
 * Car connection path used by AADisplay, instead of calling an obfuscated
 * Gearhead SystemUI method from the host process.
 */
public final class AaAutoOpenService extends Service {
    private static final String TAG = "AaAutoOpen";
    private static final String TARGET_PACKAGE = "com.carassistant.v10";
    private static final String TARGET_SERVICE = "com.carassistant.v10.AACastCarService";
    private static final long RETRY_MS = 1000L;
    private static final int MAX_MANAGER_RETRIES = 5;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Car car;
    private boolean launchRequested;
    private int managerRetries;

    private final CarConnectionCallback connectionCallback = new CarConnectionCallback() {
        @Override
        public void onConnected(Car connectedCar) {
            Log.i(TAG, "Car API connected; resolving car_1p manager");
            startProjectionWhenManagerReady(connectedCar);
        }

        @Override
        public void onDisconnected(Car disconnectedCar) {
            Log.i(TAG, "Car API disconnected");
            if (car == disconnectedCar) {
                car = null;
            }
            stopSelf();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Auto-open bridge service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (launchRequested) {
            Log.i(TAG, "Duplicate auto-open request ignored");
            return START_NOT_STICKY;
        }
        launchRequested = true;
        try {
            car = Car.createCar(getApplicationContext(), connectionCallback);
            if (car == null) {
                Log.e(TAG, "Car.createCar returned null");
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            car.connect();
            Log.i(TAG, "Car API connection requested");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to connect to Android Auto Car API", error);
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void startProjectionWhenManagerReady(Car connectedCar) {
        if (connectedCar == null) return;
        try {
            Object wrapper = connectedCar.getCarManager("car_1p");
            Object firstPartyManager = unwrapFirstPartyManager(wrapper);
            if (firstPartyManager == null) {
                if (managerRetries++ < MAX_MANAGER_RETRIES) {
                    Log.w(TAG, "car_1p manager not ready; retry " + managerRetries);
                    main.postDelayed(() -> startProjectionWhenManagerReady(connectedCar), RETRY_MS);
                } else {
                    Log.e(TAG, "Unable to resolve GMS CarFirstPartyManager");
                    stopSelf();
                }
                return;
            }

            Method startCarActivity = findStartCarActivity(firstPartyManager.getClass());
            if (startCarActivity == null) {
                Log.e(TAG, "CarFirstPartyManager.startCarActivity(Intent) not found");
                stopSelf();
                return;
            }

            Intent target = new Intent();
            target.setComponent(new ComponentName(TARGET_PACKAGE, TARGET_SERVICE));
            target.putExtra("android.intent.extra.PACKAGE_NAME", TARGET_PACKAGE);
            startCarActivity.setAccessible(true);
            startCarActivity.invoke(firstPartyManager, target);
            Log.i(TAG, "startCarActivity accepted target=" + target.getComponent());
        } catch (Throwable error) {
            Log.e(TAG, "Could not launch CarAsistant through CarFirstPartyManager", error);
            stopSelf();
        }
    }

    private static Object unwrapFirstPartyManager(Object wrapper) throws Exception {
        if (wrapper == null) return null;
        for (Class<?> type = wrapper.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if ("com.google.android.gms.car.CarFirstPartyManager".equals(
                        field.getType().getName())) {
                    field.setAccessible(true);
                    return field.get(wrapper);
                }
            }
        }
        // AADisplay's Android Auto 17.8/17.9 implementation stores the delegate in "a".
        try {
            Field delegate = wrapper.getClass().getDeclaredField("a");
            delegate.setAccessible(true);
            Object value = delegate.get(wrapper);
            if (value != null && "com.google.android.gms.car.CarFirstPartyManager".equals(
                    value.getClass().getName())) {
                return value;
            }
        } catch (NoSuchFieldException ignored) {
        }
        return null;
    }

    private static Method findStartCarActivity(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod("startCarActivity", Intent.class);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    @Override
    public void onDestroy() {
        if (car != null) {
            try {
                car.disconnect();
            } catch (Throwable error) {
                Log.w(TAG, "Car disconnect failed", error);
            }
            car = null;
        }
        main.removeCallbacksAndMessages(null);
        Log.i(TAG, "Auto-open bridge service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
