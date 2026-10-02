package com.carassistant.v10;

import com.google.android.apps.auto.sdk.CarActivity;
import com.google.android.apps.auto.sdk.CarActivityService;

/**
 * Đăng ký Car Assistant V9 là một Car App với Android Auto.
 */
public final class AACastCarService extends CarActivityService {

    @Override
    public Class<? extends CarActivity> getCarActivity() {
        return V10CarActivity.class;
    }
}
