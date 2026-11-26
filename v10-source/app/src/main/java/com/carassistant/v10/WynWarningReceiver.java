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
        // Accept only the new transparent PNG emitted by the verified WYN overlay
        // capture hook. Legacy JPEG/screen-crop senders are intentionally rejected.
        byte[] frame = intent.getByteArrayExtra("png");
        if (frame == null || frame.length == 0 || frame.length > 1024 * 1024) {
            Log.w(TAG, "Rejected missing, legacy, empty or oversized WYN popup PNG");
            return;
        }
        CRC32 crc = new CRC32();
        crc.update(frame);
        long hash = crc.getValue();
        if (hash == lastFrameHash) return;
        Bitmap bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.length);
        if (bitmap == null) { Log.w(TAG, "Could not decode WYN popup frame"); return; }
        if (bitmap.getWidth() < 80 || bitmap.getHeight() < 120
                || bitmap.getHeight() < bitmap.getWidth() * 1.2f) {
            Log.w(TAG, "Rejected non-popup WYN frame "
                    + bitmap.getWidth() + "x" + bitmap.getHeight());
            bitmap.recycle();
            return;
        }
        lastFrameHash = hash;
        GeminiCommandLogCapture.saveWynPopupSnapshot(bitmap);
        WynWarningStore.setWarning(bitmap);
        Log.i(TAG, "WYN_WARNING_UPDATED size=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " bytes=" + frame.length + " alpha=" + bitmap.hasAlpha());
        bitmap.recycle();
    }
}