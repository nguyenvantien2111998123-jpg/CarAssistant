package com.carassistant.v10;

import com.google.android.apps.auto.sdk.CarActivity;
import com.google.android.apps.auto.sdk.CarActivityService;

public final class AACastCarService
        extends CarActivityService {

    @Override
    public void onCreate() {
        super.onCreate();
        CarAssistantCore.startRecoveryMonitor(getApplicationContext(), "AACastCarService");
        CarAssistantCore.reportCoreComponent("AA_PROJECTION_SERVICE", true,
                "Android Auto bound projection service created");
    }

    @Override
    public void onDestroy() {
        CarAssistantCore.reportCoreComponent("AA_PROJECTION_SERVICE", false,
                "Android Auto projection service destroyed");
        super.onDestroy();
    }

    @Override
    public Class<? extends CarActivity>
            getCarActivity() {

        return V10CarActivity.class;
    }
}
