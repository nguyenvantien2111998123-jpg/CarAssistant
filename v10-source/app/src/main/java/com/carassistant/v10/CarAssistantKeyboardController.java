package com.carassistant.v10;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.FrameLayout;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CarAssistantKeyboardController {

    private static final String TAG =
            "CarAssistantKeyboard";

    private static final long POLL_MS =
            800L;

    private static final Pattern SHOWN =
            Pattern.compile(
                    "mInputShown\\s*=\\s*(true|false)");

    private final V10ProjectionSession backgroundSession;

    private final V10ProjectionSession appSession;

    private final CarAssistantInputBridge backgroundBridge;

    private final CarAssistantInputBridge appBridge;

    private final CarAssistantKeyboard keyboard;

    private V10ProjectionSession activeSession() {
        if (appSession != null && appSession.isActive()) return appSession;
        if (backgroundSession != null && backgroundSession.isActive()) return backgroundSession;
        return null;
    }

    private CarAssistantInputBridge activeBridge() {
        V10ProjectionSession active = activeSession();
        if (active == appSession && appBridge != null) return appBridge;
        if (active == backgroundSession && backgroundBridge != null) return backgroundBridge;
        return null;
    }

    private final Handler handler =
            new Handler(
                    Looper.getMainLooper());

    private final RootShellSession stateShell =
            new RootShellSession();

    private boolean started;

    private boolean lastShown;

    private final Runnable poller =
            new Runnable() {

        @Override
        public void run() {

            if (!started) {
                return;
            }

            if (activeSession() == null) {

                lastShown = false;
                keyboard.hide();

                handler.postDelayed(
                        this,
                        POLL_MS);

                return;
            }

            RootShellSession.EXEC.execute(
                    () -> {

                boolean shown = false;

                try {

                    String out =
                            stateShell.run(
                                    3,
                                    "dumpsys input_method "
                                    + "| grep -m 1 "
                                    + "mInputShown || true");

                    Matcher m =
                            SHOWN.matcher(out);

                    if (m.find()) {

                        shown =
                                "true".equalsIgnoreCase(
                                        m.group(1));
                    }

                } catch (Throwable t) {

                    Log.w(
                            TAG,
                            "IME state failed",
                            t);
                }

                final boolean state =
                        shown;

                lastShown = state;

                // Reconcile the view on every poll. A lifecycle/menu hide must not
                // leave lastShown=true and permanently prevent the keyboard returning.
                handler.post(
                        () -> {
                    if (!started || activeSession() == null) {
                        keyboard.hide();
                        return;
                    }
                    if (state) keyboard.show();
                    else keyboard.hide();
                });

                if (started) {

                    handler.postDelayed(
                            this,
                            POLL_MS);
                }
            });
        }
    };

    public CarAssistantKeyboardController(
            FrameLayout host,
            V10ProjectionSession backgroundSession,
            V10ProjectionSession appSession) {

        this.backgroundSession = backgroundSession;
        this.appSession = appSession;
        backgroundBridge = backgroundSession == null ? null : new CarAssistantInputBridge(backgroundSession);
        appBridge = appSession == null ? null : new CarAssistantInputBridge(appSession);

        keyboard =
                new CarAssistantKeyboard(
                        host,
                        new CarAssistantKeyboard.Action() {

                    @Override
                    public void text(
                            String value) {

                        CarAssistantInputBridge bridge = activeBridge();
                        if (bridge != null) bridge.text(value);
                    }

                    @Override
                    public void key(
                            int keyCode) {

                        CarAssistantInputBridge bridge = activeBridge();
                        if (bridge != null) bridge.key(keyCode);
                    }
                });
    }

    public void start() {

        if (started) {
            return;
        }

        started = true;

        lastShown = false;

        keyboard.hide();

        handler.removeCallbacks(
                poller);

        handler.post(poller);
    }

    public void stop() {

        started = false;

        handler.removeCallbacks(
                poller);

        keyboard.hide();
    }

    public void hide() {

        lastShown = false;

        keyboard.hide();
    }
}
