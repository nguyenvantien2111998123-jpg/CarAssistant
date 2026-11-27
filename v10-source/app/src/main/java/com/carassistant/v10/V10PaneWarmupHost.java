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
 * Pre-creates the visible right-hand V10 projection display while Android Auto
 * is connected, before the user opens CarAsistant. The ImageReader is only a
 * temporary frame sink; V10ProjectionSession replaces it with the live
 * TextureView surface without destroying/recreating the VirtualDisplay.
 */
public final class V10PaneWarmupHost {
    private static final String TAG = "CarAssistantPaneWarmup";
    public static final int DISPLAY_INDEX = 1;
    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;

    private static ImageReader imageReader;
    private static HandlerThread drainThread;
    private static V10DisplayController owner;

    private V10PaneWarmupHost() { }

    public static synchronized boolean start(Context context) {
        if (context == null) return false;
        V10DisplayController controller = V10DisplayController.get(context, DISPLAY_INDEX);
        if (imageReader != null) return controller.isReady();
        // A display already created by a live V10 session is already warm.
        if (controller.isReady()) return true;

        ImageReader reader = null;
        HandlerThread thread = null;
        try {
            reader = ImageReader.newInstance(
                    DEFAULT_WIDTH, DEFAULT_HEIGHT, PixelFormat.RGBA_8888, 3);
            thread = new HandlerThread("CarAsistant-V10-Pane-Warmup");
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            ImageReader listenerReader = reader;
            reader.setOnImageAvailableListener(source -> {
                Image frame = null;
                try {
                    frame = source.acquireLatestImage();
                } catch (IllegalStateException error) {
                    Log.w(TAG, "Warmup frame drain stopped", error);
                } finally {
                    if (frame != null) frame.close();
                }
            }, handler);

            Surface surface = reader.getSurface();
            if (!controller.ensureDisplay(surface, DEFAULT_WIDTH, DEFAULT_HEIGHT)) {
                reader.setOnImageAvailableListener(null, null);
                reader.close();
                thread.quitSafely();
                return false;
            }
            imageReader = reader;
            drainThread = thread;
            owner = controller;
            Log.i(TAG, "Visible right-pane display prewarmed; displayId="
                    + controller.getDisplayId());
            return true;
        } catch (Throwable error) {
            if (reader != null) {
                try { reader.setOnImageAvailableListener(null, null); } catch (Throwable ignored) { }
                try { reader.close(); } catch (Throwable ignored) { }
            }
            if (thread != null) thread.quitSafely();
            Log.e(TAG, "Could not prewarm visible right-pane display", error);
            return false;
        }
    }

    /** Called only after the real V10 TextureView has been bound to this display. */
    public static synchronized void onV10SurfaceAttached(V10DisplayController controller) {
        if (imageReader == null || owner != controller) return;
        closeReaderOnly();
        Log.i(TAG, "Prewarmed right-pane display handed to the live V10 TextureView");
    }

    /** Release the display only if Android Auto disconnects before V10 takes it over. */
    public static synchronized void releaseIfHeadless(Context context) {
        if (imageReader == null) return;
        V10DisplayController controller = owner;
        if (controller == null && context != null) {
            controller = V10DisplayController.get(context, DISPLAY_INDEX);
        }
        if (controller != null) controller.destroy();
        closeReaderOnly();
        Log.i(TAG, "Released unused right-pane warmup display after AA disconnect");
    }

    private static void closeReaderOnly() {
        ImageReader reader = imageReader;
        HandlerThread thread = drainThread;
        imageReader = null;
        drainThread = null;
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
