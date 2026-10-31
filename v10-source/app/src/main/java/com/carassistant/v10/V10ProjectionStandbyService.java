package com.carassistant.v10;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

public final class V10ProjectionStandbyService extends Service {
    public static final String ACTION_CONNECTED = "com.carassistant.v10.PROJECTION_CONNECTED";
    public static final String ACTION_DISCONNECTED = "com.carassistant.v10.PROJECTION_DISCONNECTED";

    private static final String TAG = "CarAssistantStandby";
    private static final String RESUME_PREFS = "carassistant_media_resume";
    private static final int DISPLAY_INDEX = 1;
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int NOTIFICATION_ID = 3491;
    private static final String CHANNEL_ID = "projection_standby";
    private static volatile V10ProjectionStandbyService instance;

    private V10DisplayController displayController;
    private ImageReader imageReader;
    private HandlerThread imageThread;
    private Handler imageHandler;
    private final Handler main = new Handler();
    private boolean connected;
    private long disconnectDeadline;
    private final Runnable disconnectStopper = new Runnable() {
        @Override public void run() {
            if (connected) return;
            if (V10SessionStore.getCurrentActivity() == null
                    || System.currentTimeMillis() >= disconnectDeadline) {
                stopSelf();
            } else {
                main.postDelayed(this, 2000L);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        displayController = V10DisplayController.get(getApplicationContext(), DISPLAY_INDEX);
        imageThread = new HandlerThread("CarAssistantProjectionStandby");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_CONNECTED : intent.getAction();
        if (ACTION_DISCONNECTED.equals(action)) {
            connected = false;
            disconnectDeadline = System.currentTimeMillis() + 30000L;
            getSharedPreferences(RESUME_PREFS, MODE_PRIVATE).edit()
                    .putBoolean("projection_connected", false).commit();
            main.removeCallbacks(disconnectStopper);
            main.postDelayed(disconnectStopper, 2000L);
            Log.i(TAG, "Android Auto disconnected; preserving the last playback checkpoint");
            return START_NOT_STICKY;
        }

        connected = true;
        main.removeCallbacks(disconnectStopper);
        getSharedPreferences(RESUME_PREFS, MODE_PRIVATE).edit()
                .putBoolean("projection_connected", true).apply();
        prepareStandby();
        return START_STICKY;
    }

    private void prepareStandby() {
        // If the car activity is already visible, its TextureView owns the display.
        // Do not replace that live surface with the hidden ImageReader sink.
        if (V10SessionStore.getCurrentActivity() != null) {
            Log.i(TAG, "CarActivity already owns the projection; leaving its surface untouched");
            return;
        }
        if (!ensureStandbyDisplay(false)) {
            Log.w(TAG, "Could not create the standby virtual display");
            return;
        }

        ComponentName target = resolveResumeTarget();
        if (target == null) {
            Log.i(TAG, "No saved projection/media app; standby display is ready without launching an app");
            return;
        }

        boolean alreadyPrepared = displayController.isLaunched()
                && target.equals(displayController.getTarget());
        if (!alreadyPrepared) {
            displayController.launchNewTask(target);
            V10SessionStore.setTarget(this, target);
            Log.i(TAG, "Prepared saved app on standby display: " + target.flattenToString());
        } else {
            Log.i(TAG, "Reusing the existing standby task: " + target.flattenToString());
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
        return launch.getComponent();
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
                || !target.getPackageName().equals(mediaPackage)) {
            return;
        }

        String category = mediaCategory(mediaPackage);
        String uri = VoiceBackgroundTaskManager.buildSearchUri(target, title, category);
        if (TextUtils.isEmpty(uri)) {
            Log.w(TAG, "No safe search URI for saved media package " + mediaPackage);
            return;
        }

        int displayId = displayController.getDisplayId();
        MediaPlaybackAccessibilityService.prepareResume(
                this, mediaPackage, title, displayId, position);
        main.postDelayed(() -> {
            if (!connected || displayController == null || !displayController.isReady()) return;
            boolean accepted = displayController.launchMediaViewIntentOnDisplay(mediaPackage, uri);
            Log.i(TAG, "Saved media search dispatched on display " + displayId
                    + "; accepted=" + accepted + "; title=" + title);
        }, 1100L);
    }

    private static String mediaCategory(String packageName) {
        String value = packageName.toLowerCase(java.util.Locale.ROOT);
        return value.contains("youtube") && !value.contains("music")
                ? "video" : "music";
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_aacast)
                .setContentTitle("CarAsistant")
                .setContentText("Đang chuẩn bị luồng chiếu Android Auto")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "CarAsistant projection standby",
                    NotificationManager.IMPORTANCE_LOW));
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

    /** Rebinds the display to its hidden sink when the car-side activity is dismissed. */
    public static void onCarActivityStopped() {
        V10ProjectionStandbyService service = instance;
        if (service != null && service.connected) {
            service.main.post(() -> service.ensureStandbyDisplay(true));
        }
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        connected = false;
        getSharedPreferences(RESUME_PREFS, MODE_PRIVATE).edit()
                .putBoolean("projection_connected", false).apply();
        main.removeCallbacksAndMessages(null);
        // If the car activity has not attached its TextureView, this service still owns
        // the sink and must release the virtual display. Otherwise the live car surface
        // remains attached and the controller must survive the service teardown.
        if (displayController != null) displayController.destroy();
        closeImageReader();
        main.removeCallbacks(disconnectStopper);
        if (imageThread != null) imageThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}