package com.carassistant.v10;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Triggers restore only for a configured USB data connection.
 * A plain charging connection (connected=true, configured=false) is ignored.
 * ADB-only configuration is also ignored; accessory/MTP/PTP/RNDIS indicate a
 * data-capable USB function rather than power-only charging.
 */
public final class UsbMediaRestoreReceiver extends BroadcastReceiver {
    private static final String TAG = "CarAssistantMediaResume";
    private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_USB_STATE.equals(intent.getAction())) return;

        boolean connected = intent.getBooleanExtra("connected", false);
        boolean configured = intent.getBooleanExtra("configured", false);
        boolean accessory = intent.getBooleanExtra("accessory", false);
        boolean mtp = intent.getBooleanExtra("mtp", false);
        boolean ptp = intent.getBooleanExtra("ptp", false);
        boolean rndis = intent.getBooleanExtra("rndis", false);
        boolean midi = intent.getBooleanExtra("midi", false);
        boolean dataFunction = accessory || mtp || ptp || rndis || midi;

        if (!connected || !configured || !dataFunction) {
            Log.i(TAG, "USB event ignored (power-only, disconnected, or no data function): "
                    + "connected=" + connected + ", configured=" + configured
                    + ", accessory=" + accessory + ", mtp=" + mtp + ", ptp=" + ptp
                    + ", rndis=" + rndis + ", midi=" + midi);
            return;
        }

        Log.i(TAG, "USB data connection detected; requesting media restore");
        MediaBootReceiver.enqueueRestore(context, "USB data connection");
    }
}
