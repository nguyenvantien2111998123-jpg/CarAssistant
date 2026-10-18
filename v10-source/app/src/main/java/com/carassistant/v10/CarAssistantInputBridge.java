package com.carassistant.v10;

import android.util.Log;

public final class CarAssistantInputBridge {

    private static final String TAG =
            "CarAssistantInputBridge";

    private final V10ProjectionSession session;

    private final RootShellSession rootShell =
            new RootShellSession();

    public CarAssistantInputBridge(
            V10ProjectionSession session) {

        this.session = session;
    }

    public void text(String value) {

        if (!session.isActive()
                || value == null
                || value.length() == 0) {
            return;
        }

        String safe =
                value
                        .replace("\\", "\\\\")
                        .replace("'", "'\\''")
                        .replace("\n", "")
                        .replace("\r", "");

        String command =
                "/system/bin/input -d "
                + session.getDisplayId()
                + " text '"
                + safe
                + "'";

        run(command);
    }

    public void key(int keyCode) {

        if (!session.isActive()) {
            return;
        }

        run(
                "/system/bin/input -d "
                + session.getDisplayId()
                + " keyevent "
                + keyCode);
    }

    private void run(String command) {

        RootShellSession.EXEC.execute(
                () -> {

                    try {

                        rootShell.run(
                                5,
                                command);

                    } catch (Throwable t) {

                        Log.w(
                                TAG,
                                "input failed",
                                t);
                    }
                });
    }

    public void destroy() {
        // Lifecycle is controlled by V2C.
    }
}
