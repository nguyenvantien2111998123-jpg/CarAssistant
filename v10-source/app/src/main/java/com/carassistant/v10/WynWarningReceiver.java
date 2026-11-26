package com.carassistant.v10;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import java.util.zip.CRC32;

/** Receives compressed live pixels captured from WYN's popup surface. */
public final class WynWarningReceiver extends BroadcastReceiver {
    private static final String ACTION = "com.carassistant.v10.action.WYN_WARNING_FRAME";
    private static final String TAG = "CarAsistantWYN";
    private static volatile long lastFrameHash = Long.MIN_VALUE;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        byte[] jpeg = intent.getByteArrayExtra("jpeg");
        if (jpeg == null || jpeg.length == 0 || jpeg.length > 512 * 1024) {
            Log.w(TAG, "Rejected empty or oversized WYN popup frame");
            return;
        }
        CRC32 crc = new CRC32();
        crc.update(jpeg);
        long hash = crc.getValue();
        if (hash == lastFrameHash) return;
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bitmap == null) { Log.w(TAG, "Could not decode WYN popup frame"); return; }
        lastFrameHash = hash;
        GeminiCommandLogCapture.saveWynPopupSnapshot(bitmap);
        WynWarningStore.setWarning(bitmap);
        Log.i(TAG, "WYN_WARNING_UPDATED size=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " bytes=" + jpeg.length);
        bitmap.recycle();
    }
}