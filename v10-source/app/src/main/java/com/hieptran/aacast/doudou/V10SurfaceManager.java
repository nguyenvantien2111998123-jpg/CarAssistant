package com.carassistant.v10;

import android.graphics.SurfaceTexture;

public final class V10SurfaceManager {
    private final V10DisplayController displayController;

    public V10SurfaceManager(V10DisplayController displayController) {
        this.displayController = displayController;
    }

    public boolean attach(SurfaceTexture texture, int width, int height) {
        if (!displayController.isReady()) {
            return displayController.ensureDisplay(texture, width, height);
        }
        displayController.attachSurface(texture, width, height);
        return true;
    }

    public void detach() { displayController.detachSurface(); }
}
