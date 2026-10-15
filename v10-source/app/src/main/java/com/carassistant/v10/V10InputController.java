package com.carassistant.v10;

import android.view.MotionEvent;
import android.view.View;

/**
 * Forwards original touch events instead of converting them to tap/swipe
 * commands or synthesizing a separate two-pointer gesture.
 */
public final class V10InputController {
    private final V10ProjectionSession session;

    public V10InputController(V10ProjectionSession session) {
        this.session = session;
    }

    public View.OnTouchListener createListener() {
        return (view, event) -> {
            if (event != null) {
                session.injectTouchEvent(event);
            }
            return true;
        };
    }
}
