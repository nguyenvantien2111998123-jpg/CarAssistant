package com.carassistant.v10;

import com.google.android.apps.auto.sdk.CarActivity;
import com.google.android.apps.auto.sdk.CarActivityService;

/**
 * Registers Car Assistant V10 with Android Auto.
 */
public final class AACastCarService
        extends CarActivityService {

    @Override
    public Class<? extends CarActivity> getCarActivity() {
        return V10CarActivity.class;
    }
}
