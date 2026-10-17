package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * Runs media search tasks on a dedicated, non-presented Virtual Display.
 * The Android Auto projection surface currently showing the map is not touched.
 *
 * Search deep links are provider-specific. A search request is not proof that
 * the provider selected or started playback; many apps require user interaction
 * or impose background playback restrictions.
 */
public final class VoiceBackgroundTaskManager {
    private static final String TAG = "CarAssistantVoiceTask";
    private static final int DISPLAY_INDEX = 2; // "Car Assistant V10 3"; never the visible map display.
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    private final V10DisplayController displayController;
    private final HandlerThread imageThread;
    private final Handler imageHandler;
    private ImageReader imageReader;
    private boolean destroyed;

    public VoiceBackgroundTaskManager(Context context) {
        Context app = context.getApplicationContext();
        displayController = V10DisplayController.get(app, DISPLAY_INDEX);
        imageThread = new HandlerThread("CarAssistantVoiceDisplay");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
    }

    /** Opens an app on the hidden voice display without changing the visible projection. */
    public synchronized boolean launchApp(ComponentName component) {
        if (component == null || !ensureHiddenDisplay()) return false;
        displayController.launchNewTask(component);
        return true;
    }

    /**
     * Sends a provider-supported search URI to the selected app on the hidden display.
     * Returns true when the task is accepted for dispatch, not when playback is verified.
     */
    public synchronized boolean search(ComponentName component, String query, String category) {
        if (component == null || query == null || query.trim().isEmpty()) return false;
        String uri = buildSearchUri(component, query.trim(), category);
        if (uri == null || !ensureHiddenDisplay()) return false;
        displayController.launchViewIntentOnDisplay(component.getPackageName(), uri);
        return true;
    }

    private boolean ensureHiddenDisplay() {
        if (destroyed) return false;
        if (imageReader == null) {
            try {
                imageReader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2);
                imageReader.setOnImageAvailableListener(reader -> {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                    } catch (IllegalStateException ignored) {
                        // The display may be closing while the final frame arrives.
                    } finally {
                        if (image != null) image.close();
                    }
                }, imageHandler);
            } catch (RuntimeException e) {
                Log.e(TAG, "Cannot create hidden display sink", e);
                closeReader();
                return false;
            }
        }
        try {
            // Do not pass ImageReader.getSurface() again after the display has been created:
            // the controller owns that Surface wrapper and may release it when rebinding.
            if (displayController.isReady()) return true;
            return displayController.ensureDisplay(imageReader.getSurface(), WIDTH, HEIGHT)
                    && displayController.isReady();
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot initialize hidden voice display", e);
            return false;
        }
    }

    public static String buildSearchUri(ComponentName component, String query, String category) {
        String pkg = component.getPackageName();
        String encoded = Uri.encode(query);

        String lowerPkg = pkg.toLowerCase(java.util.Locale.ROOT);
        if ("com.google.android.apps.youtube.music".equals(pkg) || lowerPkg.contains("youtubemusic")) {
            return "https://music.youtube.com/search?q=" + encoded;
        }
        // Official YouTube, ReVanced and ReVanced Extended may use different package IDs.
        if (lowerPkg.contains("youtube")
                || (lowerPkg.contains("revanced")
                    && ("music".equals(category) || "video".equals(category)))) {
            return "https://www.youtube.com/results?search_query=" + encoded;
        }
        if ("com.spotify.music".equals(pkg)) {
            return "spotify:search:" + encoded;
        }
        if (pkg.toLowerCase(java.util.Locale.ROOT).contains("zing")) {
            return "https://zingmp3.vn/tim-kiem/tat-ca?q=" + encoded;
        }
        if ("com.nct".equals(pkg) || pkg.toLowerCase(java.util.Locale.ROOT).contains("nhaccuatui")) {
            return "https://www.nhaccuatui.com/tim-kiem?q=" + encoded;
        }

        Log.w(TAG, "No known background search URI for package " + pkg
                + " (category=" + category + ")");
        return null;
    }

    private void closeReader() {
        if (imageReader == null) return;
        try {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
        } catch (Throwable ignored) { }
        imageReader = null;
    }

    public synchronized void destroy() {
        if (destroyed) return;
        destroyed = true;
        displayController.destroy();
        closeReader();
        imageThread.quitSafely();
    }
}
