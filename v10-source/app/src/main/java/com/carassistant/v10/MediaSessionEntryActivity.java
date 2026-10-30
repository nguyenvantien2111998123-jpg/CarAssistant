package com.carassistant.v10;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * MediaSession session-activity entry point. It signals the Gearhead-side hook
 * to open the registered CarActivityService, rather than starting V10 as a
 * phone Activity or relying on a static Activity reference across processes.
 */
public final class MediaSessionEntryActivity extends Activity {
    private static final String TAG = "CarAssistantMediaProxy";
    private static final String ACTION_OPEN_CAR_APP =
            "com.carassistant.v10.action.OPEN_CAR_APP_FROM_MEDIA";
    private static final String GEARHEAD_PACKAGE =
            "com.google.android.projection.gearhead";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Intent request = new Intent(ACTION_OPEN_CAR_APP);
            request.setPackage(GEARHEAD_PACKAGE);
            sendBroadcast(request);
            Log.i(TAG, "Requested CarAsistant V10 Car App from Gearhead");
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not request CarAsistant V10 Car App", error);
        } finally {
            finish();
        }
    }
}
