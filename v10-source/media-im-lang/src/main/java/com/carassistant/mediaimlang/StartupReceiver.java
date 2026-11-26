package com.carassistant.mediaimlang;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class StartupReceiver extends BroadcastReceiver {
    private static final String TAG = "MediaImLangStartup";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"com.carassistant.v10.ACTION_AA_CONNECTED".equals(action)) return;

        try {
            Intent service = new Intent(context, SilentMediaBrowserService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
            Log.i(TAG, "Requested Media Im Lang service for action=" + action);
        } catch (RuntimeException error) {
            Log.e(TAG, "Android rejected background service start for action=" + action, error);
        }
    }
}
