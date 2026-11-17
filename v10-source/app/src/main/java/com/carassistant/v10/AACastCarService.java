package com.carassistant.v10;

import android.util.Log;

import com.google.android.apps.auto.sdk.CarActivity;
import com.google.android.apps.auto.sdk.CarActivityService;

/**
 * Android Auto-owned projection entry point.
 *
 * The host binds this service when it launches the registered car app. That
 * binding is the reliable app lifecycle boundary; no phone Activity launch or
 * Notification Listener permission is required to bootstrap Core.
 */
public final class AACastCarService extends CarActivityService {
    private static final String TAG = "CarAsstCarService";

    @Override
    public void onCreate() {
        super.onCreate();
        CarAssistantCore.startRecoveryMonitor(getApplicationContext(), "AACastCarService:onCreate");
        CarAssistantCore.reportCoreComponent("AA_PROJECTION_SERVICE", true,
                "Android Auto bound projection service created");
        CarAssistantCore.reportAndroidAutoConnection(true);
        Log.i(TAG, "Android Auto projection service bound; Core bootstrap requested");
    }

    @Override
    public void onDestroy() {
        CarAssistantCore.reportAndroidAutoConnection(false);
        CarAssistantCore.reportCoreComponent("AA_PROJECTION_SERVICE", false,
                "Android Auto projection service destroyed");
        Log.i(TAG, "Android Auto projection service destroyed");
        super.onDestroy();
    }

    @Override
    public Class<? extends CarActivity> getCarActivity() {
        return V10CarActivity.class;
    }
}
