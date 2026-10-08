package com.carassistant.v10;

import android.content.ComponentName;

/**
 * Một ứng dụng có thể mở trong pane.
 */
public final class AppEntry {

    public final String label;
    public final String pkg;
    public final ComponentName component;

    public AppEntry(String label, String pkg, ComponentName component) {
        this.label = label;
        this.pkg = pkg;
        this.component = component;
    }
}
