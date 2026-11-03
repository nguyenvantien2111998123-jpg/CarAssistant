package com.carassistant.v10;

import android.content.Context;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

/**
 * Owns the off-screen producer used to keep V10 Display 2 alive before the
 * Android Auto CarActivity is opened. Frames are drained immediately: the
 * ImageReader is a Surface sink, not a recording buffer.
 */
public final class HeadlessProjectionHost {
    private static final String TAG = "CarAssistantHeadlessDisplay";
    public static final int DISPLAY_INDEX = 1; // V10 Display 2 (index 0 is the Home background).
    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 720;

    private static ImageReader imageReader;
    private static HandlerThread drainThread;
    private static Handler drainHandler;
    private static V10DisplayController owner;
    private static boolean handedOff;

    private HeadlessProjectionHost() { }

    public static synchronized boolean start(Context context) {
        return start(context, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    public static synchronized boolean start(Context context, int width, int height) {
        if (context == null || width < 1 || height < 1) return false;
        V10DisplayController controller = V10DisplayController.get(context, DISPLAY_INDEX);
        if (handedOff && controller.isReady()) return true;
        if (imageReader != null) return controller.isReady();

        ImageReader created = null;
        HandlerThread thread = null;
        try {
            created = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            thread = new HandlerThread("CarAsistant-Display2-Drain");
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            ImageReader listenerReader = created;
            created.setOnImageAvailableListener(source -> {
                Image image = null;
                try {
                    image = source.acquireLatestImage();
                } catch (IllegalStateException error) {
                    Log.w(TAG, "ImageReader frame drain stopped", error);
                } finally {
                    if (image != null) image.close();
                }
            }, handler);

            Surface surface = created.getSurface();
            if (!controller.ensureDisplay(surface, width, height)) {
                created.setOnImageAvailableListener(null, null);
                created.close();
                thread.quitSafely();
                return false;
            }
            imageReader = created;
            drainThread = thread;
            drainHandler = handler;
            owner = controller;
            Log.i(TAG, "Headless Display 2 ready; displayId=" + controller.getDisplayId()
                    + ", size=" + width + "x" + height);
            return true;
        } catch (Throwable error) {
            if (created != null) {
                try { created.setOnImageAvailableListener(null, null); } catch (Throwable ignored) { }
                try { created.close(); } catch (Throwable ignored) { }
            }
            if (thread != null) thread.quitSafely();
            Log.e(TAG, "Could not create headless Display 2", error);
            return false;
        }
    }

    /** Called only after VirtualDisplay.setSurface(TextureViewSurface) succeeds. */
    public static synchronized void onV10SurfaceAttached(V10DisplayController controller) {
        if (imageReader == null || owner != controller) return;
        handedOff = true;
        closeReaderOnly();
        Log.i(TAG, "Display 2 Surface handed from ImageReader to V10 TextureView");
    }

    /** On AA disconnect, destroy the display only while it is still headless. */
    public static synchronized void releaseIfHeadless(Context context) {
        if (imageReader == null) return; // V10 has taken ownership; do not tear down its live UI.
        V10DisplayController controller = owner;
        if (controller == null && context != null) {
            controller = V10DisplayController.get(context, DISPLAY_INDEX);
        }
        if (controller != null) controller.destroy();
        closeReaderOnly();
        Log.i(TAG, "Released headless Display 2 after Android Auto disconnect");
    }

    public static synchronized boolean isHeadless() {
        return imageReader != null;
    }

    private static void closeReaderOnly() {
        ImageReader reader = imageReader;
        HandlerThread thread = drainThread;
        imageReader = null;
        drainThread = null;
        drainHandler = null;
        owner = null;
        if (reader != null) {
            try { reader.setOnImageAvailableListener(null, null); } catch (Throwable ignored) { }
            try { reader.close(); } catch (Throwable ignored) { }
        }
        if (thread != null) {
            try { thread.quitSafely(); } catch (Throwable ignored) { }
        }
    }
}
