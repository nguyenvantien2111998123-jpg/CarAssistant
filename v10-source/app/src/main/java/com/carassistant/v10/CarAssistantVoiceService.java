package com.carassistant.v10;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.carassistant.v10.voice.VoiceAssistantController;
import com.carassistant.v10.voice.VoiceCommandRouter;

/**
 * Owns the voice recognition lifecycle independently from V10CarActivity.
 *
 * The service is started while the Android Auto CarActivity is visible, so the
 * microphone foreground-service start is initiated from an allowed foreground
 * context. Projection commands are delegated dynamically to the current
 * CarActivity; the service never retains an Activity reference.
 */
public final class CarAssistantVoiceService extends Service {
    public static final String ACTION_START =
            "com.carassistant.v10.voice.action.START";
    public static final String ACTION_TOGGLE =
            "com.carassistant.v10.voice.action.TOGGLE";
    public static final String ACTION_CANCEL =
            "com.carassistant.v10.voice.action.CANCEL";
    public static final String ACTION_STATE =
            "com.carassistant.v10.voice.action.STATE";

    public static final String EXTRA_LISTENING = "listening";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_TRANSCRIPT = "transcript";
    public static final String EXTRA_SHOW_TRANSCRIPT = "show_transcript";

    private static final String CHANNEL_ID = "carassistant_voice";
    private static final int NOTIFICATION_ID = 4041;

    private VoiceAssistantController controller;
    private boolean lastListening;
    private String lastStatus = "Trợ lý giọng nói sẵn sàng.";
    private String lastTranscript = "";
    private boolean lastShowTranscript;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        VoiceCommandRouter router = new VoiceCommandRouter(new VoiceCommandRouter.Host() {
            @Override
            public String openApp(String spokenName) {
                return V10ProjectionCoordinator.openApp(
                        CarAssistantVoiceService.this, spokenName);
            }

            @Override
            public String openVoiceTask(String category, String query, String explicitProvider) {
                return V10ProjectionCoordinator.openVoiceTask(
                        CarAssistantVoiceService.this, category, query, explicitProvider);
            }

            @Override
            public void showAppPicker() {
                V10ProjectionCoordinator.showAppPicker(CarAssistantVoiceService.this);
            }

            @Override
            public void goHome() {
                V10ProjectionCoordinator.goHome(CarAssistantVoiceService.this);
            }
        });

        controller = new VoiceAssistantController(getApplicationContext(),
                (listening, status, transcript, showTranscript) -> publishState(
                        listening, status, transcript, showTranscript),
                router);
        publishState(false, "Trợ lý giọng nói sẵn sàng.", "", false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_TOGGLE.equals(action)) {
            if (controller != null) controller.toggleListening();
        } else if (ACTION_CANCEL.equals(action)) {
            if (controller != null) controller.cancelListening();
        } else {
            publishState(lastListening, lastStatus, lastTranscript, lastShowTranscript);
        }
        // Do not let Android restart a microphone FGS in the background after process death.
        return START_NOT_STICKY;
    }

    private void publishState(boolean listening, String status,
                              String transcript, boolean showTranscript) {
        lastListening = listening;
        lastStatus = status == null ? "" : status;
        lastTranscript = transcript == null ? "" : transcript;
        lastShowTranscript = showTranscript;
        Intent state = new Intent(ACTION_STATE).setPackage(getPackageName());
        state.putExtra(EXTRA_LISTENING, listening);
        state.putExtra(EXTRA_STATUS, lastStatus);
        state.putExtra(EXTRA_TRANSCRIPT, lastTranscript);
        state.putExtra(EXTRA_SHOW_TRANSCRIPT, showTranscript);
        sendBroadcast(state);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "CarAsistant voice assistant",
                        NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_aacast)
                    .setContentTitle("CarAsistant")
                    .setContentText("Trợ lý giọng nói sẵn sàng khi kết nối Android Auto")
                    .setOngoing(true)
                    .build();
        }
        return new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_aacast)
                .setContentTitle("CarAsistant")
                .setContentText("Trợ lý giọng nói sẵn sàng khi kết nối Android Auto")
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (controller != null) {
            controller.destroy();
            controller = null;
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
