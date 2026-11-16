package com.carassistant.v10.core.car;

/** Pluggable backend for the display surface owned by a live car session. */
public interface CarDisplayBackend {
    /** Stable diagnostic identifier, e.g. legacy-v10 or shizuku-virtual-display. */
    String getId();
    boolean isAvailable();
    /** Attach to a live car session; true means the display surface is ready. */
    boolean attach();
    /** Detach the UI surface while preserving backend-owned tasks when supported. */
    void detach();
    boolean launchApp(String packageName, String activityName);
    boolean showHome();
}
