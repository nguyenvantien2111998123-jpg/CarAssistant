package com.carassistant.v10;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.carassistant.v10.voice.VoiceAssistantController;
import com.carassistant.v10.voice.VoiceCommandRouter;

/**
 * Owns speech recognition, command routing and Vietnamese TTS independently
 * of the Android Auto Activity. The Activity is only a projection/UI adapter.
 *
 * A steering-wheel double press can prepare this microphone FGS through the
 * existing System Framework LSPosed hook. The actual listening session remains
 * push-to-talk and is requested separately by the steering event receiver.
 */
public final class CarAsistantVoiceService extends Service {
    private static final String TAG = "CarAsistantVoice";
    private static final String CHANNEL_ID = "carassistant_voice";
    private static final int NOTIFICATION_ID = 7301;
    private static final long PENDING_TOGGLE_TIMEOUT_MS = 3000L;

    public static final String ACTION_PREPARE = "com.carassistant.v10.voice.PREPARE";
    public static final String ACTION_TOGGLE = "com.carassistant.v10.voice.TOGGLE";
    public static final String ACTION_CANCEL = "com.carassistant.v10.voice.CANCEL";
    public static final String ACTION_STATE_CHANGED = "com.carassistant.v10.voice.STATE_CHANGED";

    public static final String EXTRA_LISTENING = "listening";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_TRANSCRIPT = "transcript";
    public static final String EXTRA_SHOW_TRANSCRIPT = "show_transcript";

    private static final Object PENDING_TOGGLE_LOCK = new Object();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static boolean pendingSteeringToggle;
    private static final Runnable EXPIRE_PENDING_TOGGLE = () -> {
        synchronized (PENDING_TOGGLE_LOCK) {
            pendingSteeringToggle = false;
        }
    };

    private static volatile CarAsistantVoiceService instance;
    private VoiceAssistantController controller;
    private volatile boolean lastListening;
    private volatile String lastStatus = "Trợ lý giọng nói sẵn sàng.";
    private volatile String lastTranscript = "";
    private volatile boolean lastShowTranscript;

    public static boolean ensureStarted(Context context) {
        if (context == null) return false;
        CarAsistantVoiceService current = instance;
        if (current != null && current.controller != null) return true;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            publishUnavailable(context, "Cần cấp quyền micro cho CarAsistant.");
            return false;
        }
        return start(context, null);
    }

    /** Used by visible UI controls; starting here is allowed while the Activity is visible. */
    public static boolean toggleListening(Context context) {
        if (context == null) return false;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            publishUnavailable(context, "Cần cấp quyền micro cho CarAsistant.");
            return false;
        }
        CarAsistantVoiceService current = instance;
        if (current != null && current.controller != null) {
            current.controller.toggleListening();
            return true;
        }
        return start(context, ACTION_TOGGLE);
    }

    /**
     * Steering receiver must never attempt to create a microphone FGS from the
     * app's background UID. The System Framework hook prepares the service first.
     * This method queues the toggle briefly to cover the service-start race.
     */
    public static void requestSteeringToggle(Context context) {
        if (context == null) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            publishUnavailable(context, "Cần cấp quyền micro cho CarAsistant.");
            return;
        }

        CarAsistantVoiceService current = instance;
        if (current != null && current.controller != null) {
            current.controller.toggleListening();
            return;
        }

        synchronized (PENDING_TOGGLE_LOCK) {
            pendingSteeringToggle = true;
        }
        MAIN_HANDLER.removeCallbacks(EXPIRE_PENDING_TOGGLE);
        MAIN_HANDLER.postDelayed(EXPIRE_PENDING_TOGGLE, PENDING_TOGGLE_TIMEOUT_MS);
        if (current != null) {
            MAIN_HANDLER.post(current::consumePendingSteeringToggle);
        }
    }

    public static void requestState(Context context) {
        CarAsistantVoiceService current = instance;
        if (current != null) current.publishCurrentState();
    }

    private static boolean start(Context context, String action) {
        try {
            Intent intent = new Intent(context, CarAsistantVoiceService.class);
            if (action != null) intent.setAction(action);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to start microphone foreground service", error);
            publishUnavailable(context, "Trợ lý giọng nói chưa thể khởi động trong nền.");
            return false;
        }
    }

    private static void publishUnavailable(Context context, String status) {
        Intent state = new Intent(ACTION_STATE_CHANGED);
        state.setPackage(context.getPackageName());
        state.putExtra(EXTRA_LISTENING, false);
        state.putExtra(EXTRA_STATUS, status);
        state.putExtra(EXTRA_TRANSCRIPT, "");
        state.putExtra(EXTRA_SHOW_TRANSCRIPT, false);
        context.sendBroadcast(state);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Microphone permission is missing");
            instance = null;
            publishUnavailable(this, "Cần cấp quyền micro cho CarAsistant.");
            stopSelf();
            return;
        }

        try {
            createNotificationChannel();
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            controller = new VoiceAssistantController(
                    getApplicationContext(),
                    this::publishState,
                    createCommandRouter());
            publishState(false, "Trợ lý giọng nói sẵn sàng.", "", false);
            consumePendingSteeringToggle();
        } catch (RuntimeException error) {
            Log.e(TAG, "Voice service initialization failed", error);
            instance = null;
            publishUnavailable(this, "Không thể khởi động dịch vụ trợ lý giọng nói.");
            stopSelf();
        }
    }

    private void consumePendingSteeringToggle() {
        boolean pending;
        synchronized (PENDING_TOGGLE_LOCK) {
            pending = pendingSteeringToggle;
            pendingSteeringToggle = false;
        }
        if (pending) {
            MAIN_HANDLER.removeCallbacks(EXPIRE_PENDING_TOGGLE);
            if (controller != null) controller.toggleListening();
        }
    }

    private VoiceCommandRouter createCommandRouter() {
        return new VoiceCommandRouter(
                new VoiceCommandExecutionGateway(getApplicationContext()));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "CarAsistant Voice", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Duy trì trợ lý giọng nói để nhận lệnh từ nút vô lăng");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = launch == null ? null : PendingIntent.getActivity(
                this, NOTIFICATION_ID, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_aacast)
                .setContentTitle("CarAsistant – Trợ lý giọng nói")
                .setContentText("Sẵn sàng nhận lệnh từ nút vô lăng")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (contentIntent != null) builder.setContentIntent(contentIntent);
        return builder.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (controller == null) return START_NOT_STICKY;
        String action = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(action)) {
            controller.toggleListening();
        } else if (ACTION_CANCEL.equals(action)) {
            controller.cancelListening();
        } else if (ACTION_PREPARE.equals(action)) {
            consumePendingSteeringToggle();
        }
        return START_STICKY;
    }

    private void publishCurrentState() {
        publishState(lastListening, lastStatus, lastTranscript, lastShowTranscript);
    }

    private void publishState(boolean listening, String status, String transcript, boolean showTranscript) {
        lastListening = listening;
        lastStatus = status == null ? "" : status;
        lastTranscript = transcript == null ? "" : transcript;
        lastShowTranscript = showTranscript;

        Intent state = new Intent(ACTION_STATE_CHANGED);
        state.setPackage(getPackageName());
        state.putExtra(EXTRA_LISTENING, lastListening);
        state.putExtra(EXTRA_STATUS, lastStatus);
        state.putExtra(EXTRA_TRANSCRIPT, lastTranscript);
        state.putExtra(EXTRA_SHOW_TRANSCRIPT, lastShowTranscript);
        sendBroadcast(state);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (controller != null) {
            controller.destroy();
            controller = null;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
