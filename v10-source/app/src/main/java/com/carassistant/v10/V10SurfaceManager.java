package com.carassistant.v10;

import android.graphics.SurfaceTexture;

public final class V10SurfaceManager {
    private final V10DisplayController displayController;

    public V10SurfaceManager(V10DisplayController displayController) {
        this.displayController = displayController;
    }

    public boolean attach(SurfaceTexture texture, int width, int height) {
        boolean attached;
        if (!displayController.isReady()) {
            attached = displayController.ensureDisplay(texture, width, height);
        } else {
            attached = displayController.attachSurface(texture, width, height);
        }
        if (attached) {
            // The new TextureView Surface is now bound; only now may the headless
            // ImageReader producer be closed.
            HeadlessProjectionHost.onV10SurfaceAttached(displayController);
        }
        return attached;
    }

    public void detach() { displayController.detachSurface(); }
}
