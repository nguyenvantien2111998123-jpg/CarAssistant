package com.carassistant.v10;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

public final class V10ProjectionStandbyService extends Service {
    private static final String TAG = "CarAssistantStandby";
    private static final String RESUME_PREFS = "carassistant_media_resume";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final int DISPLAY_INDEX = 1;
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    private static volatile V10ProjectionStandbyService instance;
    private final IBinder binder = new Binder();
    private V10DisplayController displayController;
    private ImageReader imageReader;
    private HandlerThread imageThread;
    private Handler imageHandler;
    private final Handler main = new Handler();
    private boolean connected;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        displayController = V10DisplayController.get(getApplicationContext(), DISPLAY_INDEX);
        imageThread = new HandlerThread("CarAssistantProjectionStandby");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (!isGearheadCaller(intent)) {
            Log.w(TAG, "Rejected bind request from a non-Android-Auto package");
            return null;
        }
        connected = true;
        getSharedPreferences(RESUME_PREFS, MODE_PRIVATE).edit()
                .putBoolean("projection_connected", true).apply();
        main.post(this::prepareStandby);
        Log.i(TAG, "Android Auto bound the phone-side projection standby service");
        return binder;
    }

    private boolean isGearheadCaller(Intent intent) {
        // The service is exported only so Gearhead can bind across package UIDs.
        // Verify the explicit client marker supplied by our in-process AA hook.
        return intent != null && GEARHEAD.equals(
                intent.getStringExtra("carassistant_client_package"));
    }

    private void prepareStandby() {
        if (!connected) return;
        // The live car activity owns this display if it is already visible.
        if (V10SessionStore.getCurrentActivity() != null) {
            Log.i(TAG, "CarActivity already owns the projection surface");
            return;
        }
        if (!ensureStandbyDisplay(false)) {
            Log.w(TAG, "Could not create the standby virtual display");
            return;
        }

        ComponentName target = resolveResumeTarget();
        if (target == null) {
            Log.i(TAG, "No saved media/app target; standby display is ready");
            return;
        }

        boolean alreadyPrepared = displayController.isLaunched()
                && target.equals(displayController.getTarget());
        if (!alreadyPrepared) {
            displayController.launchNewTask(target);
            V10SessionStore.setTarget(this, target);
            Log.i(TAG, "Prepared saved app on standby display: " + target.flattenToString());
        } else {
            Log.i(TAG, "Reusing prepared app task: " + target.flattenToString());
        }
        restoreSavedMedia(target);
    }

    private boolean ensureStandbyDisplay(boolean forceRebind) {
        if (displayController == null) return false;
        if (!forceRebind && displayController.isReady()
                && V10SessionStore.getCurrentActivity() != null) return true;
        if (imageReader == null) {
            try {
                imageReader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2);
                imageReader.setOnImageAvailableListener(reader -> {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                    } catch (IllegalStateException ignored) {
                    } finally {
                        if (image != null) image.close();
                    }
                }, imageHandler);
            } catch (RuntimeException error) {
                Log.e(TAG, "Unable to create standby image sink", error);
                closeImageReader();
                return false;
            }
        }
        try {
            return displayController.ensureDisplay(imageReader.getSurface(), WIDTH, HEIGHT)
                    && displayController.isReady()
                    && displayController.getDisplayId() > 0;
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to initialize standby display", error);
            return false;
        }
    }

    private ComponentName resolveResumeTarget() {
        android.content.SharedPreferences prefs =
                getSharedPreferences(RESUME_PREFS, MODE_PRIVATE);
        String mediaPackage = prefs.getString("last_media_package", "");
        String title = prefs.getString("resume_title", "");
        boolean checkpointValid = prefs.getBoolean("resume_valid", false)
                && prefs.getBoolean("resume_was_playing", false)
                && System.currentTimeMillis() - prefs.getLong("resume_saved_at", 0L)
                < 7L * 24L * 60L * 60L * 1000L;
        if (checkpointValid && !TextUtils.isEmpty(mediaPackage) && !TextUtils.isEmpty(title)) {
            ComponentName mediaComponent = launchComponentForPackage(mediaPackage);
            if (mediaComponent != null) return mediaComponent;
        }

        ComponentName saved = V10SessionStore.getTarget(this);
        if (saved != null && isInstalled(saved.getPackageName())) return saved;
        if (!TextUtils.isEmpty(mediaPackage)) return launchComponentForPackage(mediaPackage);
        return null;
    }

    private ComponentName launchComponentForPackage(String packageName) {
        if (TextUtils.isEmpty(packageName) || !isInstalled(packageName)) return null;
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) return null;
        ComponentName component = launch.getComponent();
        if (component != null) return component;
        android.content.pm.ResolveInfo resolved = getPackageManager().resolveActivity(launch, 0);
        if (resolved == null || resolved.activityInfo == null) return null;
        return new ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name);
    }

    private boolean isInstalled(String packageName) {
        try {
            getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    private void restoreSavedMedia(ComponentName target) {
        android.content.SharedPreferences prefs =
                getSharedPreferences(RESUME_PREFS, MODE_PRIVATE);
        String mediaPackage = prefs.getString("last_media_package", "");
        String title = prefs.getString("resume_title", "");
        long position = Math.max(0L, prefs.getLong("resume_position", 0L));
        boolean wasPlaying = prefs.getBoolean("resume_was_playing", false);
        if (!prefs.getBoolean("resume_valid", false) || !wasPlaying || TextUtils.isEmpty(title)
                || !target.getPackageName().equals(mediaPackage)) return;

        String category = mediaCategory(mediaPackage);
        String uri = VoiceBackgroundTaskManager.buildSearchUri(target, title, category);
        if (TextUtils.isEmpty(uri)) {
            Log.w(TAG, "No supported search URI for saved media package " + mediaPackage);
            return;
        }

        int displayId = displayController.getDisplayId();
        MediaPlaybackAccessibilityService.prepareResume(
                this, mediaPackage, title, displayId, position);
        main.postDelayed(() -> {
            if (!connected || displayController == null || !displayController.isReady()) return;
            displayController.launchMediaViewIntentOnDisplay(mediaPackage, uri);
            Log.i(TAG, "Restoration search dispatched on display " + displayId);
        }, 1100L);
    }

    private static String mediaCategory(String packageName) {
        String value = packageName.toLowerCase(java.util.Locale.ROOT);
        return value.contains("youtube") && !value.contains("music") ? "video" : "music";
    }

    public static void onCarActivityStopped() {
        V10ProjectionStandbyService service = instance;
        if (service != null && service.connected) {
            service.main.post(() -> service.ensureStandbyDisplay(true));
        }
    }

    public static void onCarActivityDestroyed() {
        V10ProjectionStandbyService service = instance;
        if (service != null && service.connected) {
            service.main.post(() -> service.ensureStandbyDisplay(true));
        }
    }

    private void closeImageReader() {
        if (imageReader == null) return;
        try {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
        } catch (Throwable ignored) {
        }
        imageReader = null;
    }

    @Override
    public void onDestroy() {
        connected = false;
        if (instance == this) instance = null;
        getSharedPreferences(RESUME_PREFS, MODE_PRIVATE).edit()
                .putBoolean("projection_connected", false).apply();
        main.removeCallbacksAndMessages(null);
        if (displayController != null) displayController.destroy();
        closeImageReader();
        if (imageThread != null) imageThread.quitSafely();
        super.onDestroy();
    }
}