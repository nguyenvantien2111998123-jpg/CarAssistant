package com.carassistant.v10;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

public final class V10ProjectionService extends Service {

    private static final String TAG =
            "CarAssistantV10Service";

    private static final String CHANNEL_ID =
            "v10_projection";

    private static final int NOTIFICATION_ID =
            10010;

    private static volatile V10ProjectionService instance;

    private final Object lock =
            new Object();

    private ComponentName target;

    private VirtualDisplay virtualDisplay;

    private Display display;

    private Surface surface;

    private int width;

    private int height;

    private boolean destroyed;


    public static void ensureStarted(
            android.content.Context context) {

        Intent intent =
                new Intent(
                        context,
                        V10ProjectionService.class);

        if (Build.VERSION.SDK_INT >= 26) {

            context.startForegroundService(
                    intent);

        } else {

            context.startService(
                    intent);
        }
    }


    private static V10ProjectionService getInstance() {

        return instance;
    }


    @Override
    public void onCreate() {

        super.onCreate();

        instance = this;

        createNotificationChannel();

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= 26) {

            builder =
                    new Notification.Builder(
                            this,
                            CHANNEL_ID);

        } else {

            builder =
                    new Notification.Builder(
                            this);
        }

        Notification notification =
                builder
                        .setSmallIcon(
                                android.R.drawable
                                        .ic_menu_view)
                        .setContentTitle(
                                "Car Assistant V10")
                        .setContentText(
                                "Projection engine running")
                        .setOngoing(true)
                        .build();

        startForeground(
                NOTIFICATION_ID,
                notification);
    }


    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId) {

        return START_STICKY;
    }


    public static void setTarget(
            android.content.Context context,
            ComponentName component) {

        ensureStarted(context);

        V10ProjectionService service =
                getInstance();

        if (service != null) {

            service.setTargetInternal(
                    component);
        }
    }


    public static void attachSurface(
            android.content.Context context,
            Surface newSurface,
            int newWidth,
            int newHeight) {

        ensureStarted(context);

        V10ProjectionService service =
                getInstance();

        if (service != null) {

            service.attachSurfaceInternal(
                    newSurface,
                    newWidth,
                    newHeight);
        }
    }


    public static void detachSurface() {

        V10ProjectionService service =
                getInstance();

        if (service != null) {

            service.detachSurfaceInternal();
        }
    }


    public static int getDisplayId() {

        V10ProjectionService service =
                getInstance();

        if (service == null) {
            return -1;
        }

        return service.getDisplayIdInternal();
    }


    public static boolean isActive() {

        V10ProjectionService service =
                getInstance();

        return service != null
                && service.isActiveInternal();
    }


    public static void clearTarget() {

        V10ProjectionService service =
                getInstance();

        if (service != null) {

            service.clearTargetInternal();
        }
    }


    public static void tap(
            int x,
            int y) {

        V10ProjectionService service =
                getInstance();

        if (service != null) {

            service.inputInternal(
                    "tap "
                            + x
                            + " "
                            + y);
        }
    }


    public static void swipe(
            int x1,
            int y1,
            int x2,
            int y2,
            long duration) {

        V10ProjectionService service =
                getInstance();

        if (service != null) {

            long d =
                    Math.max(
                            80L,
                            Math.min(
                                    800L,
                                    duration));

            service.inputInternal(
                    "swipe "
                            + x1
                            + " "
                            + y1
                            + " "
                            + x2
                            + " "
                            + y2
                            + " "
                            + d);
        }
    }


    public static void back() {

        V10ProjectionService service =
                getInstance();

        if (service != null) {

            service.inputInternal(
                    "keyevent 4");
        }
    }


    private void setTargetInternal(
            ComponentName component) {

        synchronized (lock) {

            if (destroyed) {
                return;
            }

            if (component == null) {

                clearTargetLocked();

                return;
            }

            /*
             * Same target:
             *
             * Do NOT recreate the VirtualDisplay.
             */
            if (component.equals(target)) {
                return;
            }

            target = component;

            /*
             * A different application requires
             * a different projection display.
             */
            releaseDisplayLocked();

            ensureDisplayLocked();
        }
    }


    private void attachSurfaceInternal(
            Surface newSurface,
            int newWidth,
            int newHeight) {

        synchronized (lock) {

            if (destroyed
                    || newSurface == null
                    || newWidth < 1
                    || newHeight < 1) {

                return;
            }

            width = newWidth;

            height = newHeight;


            if (surface != null
                    && surface != newSurface) {

                try {

                    surface.release();

                } catch (Throwable ignored) {
                }
            }

            surface = newSurface;


            /*
             * First connection:
             */
            if (virtualDisplay == null) {

                ensureDisplayLocked();

                return;
            }


            /*
             * Activity/TextureView may have been
             * recreated.
             *
             * Keep the existing VirtualDisplay
             * and only replace its Surface.
             */
            try {

                virtualDisplay.setSurface(
                        newSurface);

            } catch (Throwable error) {

                Log.w(
                        TAG,
                        "VirtualDisplay.setSurface failed",
                        error);

                /*
                 * Only recreate the display if
                 * setSurface() genuinely failed.
                 */
                releaseDisplayLocked();

                ensureDisplayLocked();
            }
        }
    }


    private void detachSurfaceInternal() {

        synchronized (lock) {

            /*
             * IMPORTANT:
             *
             * Do NOT release VirtualDisplay here.
             *
             * This is the key difference from
             * the old V10ProjectionSession.
             */
            if (virtualDisplay != null) {

                try {

                    virtualDisplay.setSurface(
                            null);

                } catch (Throwable error) {

                    Log.w(
                            TAG,
                            "VirtualDisplay surface detach failed",
                            error);
                }
            }


            if (surface != null) {

                try {

                    surface.release();

                } catch (Throwable ignored) {
                }

                surface = null;
            }
        }
    }


    private void ensureDisplayLocked() {

        if (destroyed
                || target == null
                || surface == null
                || width < 1
                || height < 1
                || virtualDisplay != null) {

            return;
        }

        try {

            DisplayManager manager =
                    (DisplayManager)
                            getSystemService(
                                    DISPLAY_SERVICE);

            if (manager == null) {

                throw new IllegalStateException(
                        "DisplayManager unavailable");
            }


            VirtualDisplay vd =
                    manager.createVirtualDisplay(
                            V10Display.nameFor(0),
                            width,
                            height,
                            160,
                            surface,
                            10);

            if (vd == null) {

                throw new IllegalStateException(
                        "Cannot create virtual display");
            }


            Display d =
                    vd.getDisplay();

            if (d == null) {

                vd.release();

                throw new IllegalStateException(
                        "Virtual display has no Display");
            }


            virtualDisplay = vd;

            display = d;


            /*
             * Only launch when a NEW VirtualDisplay
             * has actually been created.
             *
             * Returning from Activity does not
             * create another launch.
             */
            launchTargetLocked();

        } catch (Throwable error) {

            Log.w(
                    TAG,
                    "Display creation failed",
                    error);

            releaseDisplayLocked();
        }
    }


    private void launchTargetLocked() {

        if (display == null
                || target == null) {

            return;
        }

        final int displayId =
                display.getDisplayId();

        final int userId =
                Process.myUid() / 100000;

        final String flat =
                target
                        .flattenToString()
                        .replace(
                                "'",
                                "'\"'\"'");


        /*
         * No force-stop.
         *
         * --activity-reorder-to-front is retained
         * only for the initial creation of a new
         * projection display.
         *
         * This method is NOT called merely because
         * Activity resumes.
         */
        final String command =
                "/system/bin/am start"
                        + " --user "
                        + userId
                        + " --display "
                        + displayId
                        + " --activity-reorder-to-front"
                        + " -n '"
                        + flat
                        + "'";


        RootShellSession.EXEC.execute(
                () -> {

                    RootShellSession shell =
                            new RootShellSession();

                    try {

                        shell.run(
                                10,
                                command);

                    } catch (Throwable error) {

                        Log.w(
                                TAG,
                                "Launch target failed",
                                error);
                    }
                });
    }


    private void inputInternal(
            String arguments) {

        final int displayId =
                getDisplayIdInternal();

        if (displayId < 0) {
            return;
        }


        final String command =
                "/system/bin/input -d "
                        + displayId
                        + " "
                        + arguments;


        RootShellSession.EXEC.execute(
                () -> {

                    RootShellSession shell =
                            new RootShellSession();

                    try {

                        shell.run(
                                5,
                                command);

                    } catch (Throwable error) {

                        Log.w(
                                TAG,
                                "Input failed",
                                error);
                    }
                });
    }


    private int getDisplayIdInternal() {

        synchronized (lock) {

            if (display == null) {
                return -1;
            }

            return display.getDisplayId();
        }
    }


    private boolean isActiveInternal() {

        synchronized (lock) {

            return !destroyed
                    && target != null
                    && virtualDisplay != null
                    && display != null;
        }
    }


    private void clearTargetInternal() {

        synchronized (lock) {

            clearTargetLocked();
        }
    }


    private void clearTargetLocked() {

        target = null;

        releaseDisplayLocked();
    }


    private void releaseDisplayLocked() {

        if (virtualDisplay != null) {

            try {

                virtualDisplay.release();

            } catch (Throwable ignored) {
            }
        }

        virtualDisplay = null;

        display = null;


        if (surface != null) {

            try {

                surface.release();

            } catch (Throwable ignored) {
            }
        }

        surface = null;
    }


    @Override
    public void onDestroy() {

        synchronized (lock) {

            destroyed = true;

            target = null;

            releaseDisplayLocked();
        }


        if (instance == this) {

            instance = null;
        }


        super.onDestroy();
    }


    @Override
    public IBinder onBind(
            Intent intent) {

        return null;
    }


    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        NotificationManager manager =
                (NotificationManager)
                        getSystemService(
                                NOTIFICATION_SERVICE);

        if (manager != null) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Car Assistant V10 Projection",
                            NotificationManager
                                    .IMPORTANCE_LOW);

            manager.createNotificationChannel(
                    channel);
        }
    }
}
