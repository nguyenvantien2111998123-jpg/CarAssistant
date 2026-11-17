package com.carassistant.v10;

import android.util.Log;

import com.google.android.apps.auto.sdk.CarActivity;
import com.google.android.apps.auto.sdk.CarActivityService;

/**
 * Host-bound projection entry point.
 *
 * Its lifecycle represents the CarAsistant projection session, NOT the
 * physical Android Auto connection. GearheadDockPatcher reports host
 * connection state separately from Gearhead's projection process.
 */
public final class AACastCarService extends CarActivityService {
    private static final String TAG = "CarAsstCarService";

    @Override
    public void onCreate() {
        super.onCreate();
        CarAssistantCore.startRecoveryMonitor(getApplicationContext(),
                "AACastCarService:onCreate");
        CarAssistantCore.reportCoreComponent("AA_PROJECTION_SERVICE", true,
                "Android Auto bound CarAsistant projection service created");
        Log.i(TAG, "CarAsistant projection service bound; Core coordinator ready");
    }

    @Override
    public void onDestroy() {
        CarAssistantCore.reportCoreComponent("AA_PROJECTION_SERVICE", false,
                "CarAsistant projection service destroyed");
        Log.i(TAG, "CarAsistant projection service destroyed");
        super.onDestroy();
    }

    @Override
    public Class<? extends CarActivity> getCarActivity() {
        return V10CarActivity.class;
    }
}
