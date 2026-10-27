package com.carassistant.v10;

import android.content.pm.ActivityInfo;

import com.google.android.apps.auto.sdk.CarActivity;
import com.google.android.apps.auto.sdk.CarActivityService;

/**
 * Android Auto entry point for the CarAsistant V10 projection Activity.
 *
 * Keep this service as the single registration point; Gearhead owns the
 * lifecycle and calls getCarActivity() when it launches the Car App.
 */
public final class AACastCarService extends CarActivityService {

    @Override
    public Class<? extends CarActivity> getCarActivity() {
        return V10CarActivity.class;
    }

    /**
     * Match AADisplay's handling of configuration changes so the host can
     * keep this CarActivity instance across projection/display configuration
     * changes rather than needlessly recreating its UI.
     */
    @Override
    public int getHandledConfigChanges() {
        return ActivityInfo.CONFIG_MCC
                | ActivityInfo.CONFIG_MNC
                | ActivityInfo.CONFIG_LOCALE
                | ActivityInfo.CONFIG_TOUCHSCREEN
                | ActivityInfo.CONFIG_KEYBOARD
                | ActivityInfo.CONFIG_KEYBOARD_HIDDEN
                | ActivityInfo.CONFIG_NAVIGATION
                | ActivityInfo.CONFIG_ORIENTATION
                | ActivityInfo.CONFIG_SCREEN_LAYOUT
                | ActivityInfo.CONFIG_UI_MODE
                | ActivityInfo.CONFIG_SCREEN_SIZE
                | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
                | ActivityInfo.CONFIG_DENSITY
                | ActivityInfo.CONFIG_LAYOUT_DIRECTION
                | ActivityInfo.CONFIG_COLOR_MODE
                | ActivityInfo.CONFIG_FONT_SCALE
                | ActivityInfo.CONFIG_FONT_WEIGHT_ADJUSTMENT;
    }
}
