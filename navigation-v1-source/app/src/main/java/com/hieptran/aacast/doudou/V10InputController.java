package com.carassistant.v10;

import android.view.MotionEvent;
import android.view.View;

public final class V10InputController {

    private final V10ProjectionSession session;

    private float downX;
    private float downY;

    private long downTime;

    private boolean moved;

    private static final float MOVE_SLOP =
            12f;

    private static final long
            SWIPE_MIN_DURATION = 80L;

    private static final long
            SWIPE_MAX_DURATION = 800L;

    public V10InputController(
            V10ProjectionSession session) {

        this.session = session;
    }

    public View.OnTouchListener
            createListener() {

        return (view, event) -> {

            switch (
                    event.getActionMasked()) {

                case MotionEvent.ACTION_DOWN:

                    downX =
                            event.getX();

                    downY =
                            event.getY();

                    downTime =
                            event.getEventTime();

                    moved = false;

                    return true;

                case MotionEvent.ACTION_MOVE:

                    if (event.getPointerCount()
                            == 1) {

                        float dx =
                                event.getX()
                                        - downX;

                        float dy =
                                event.getY()
                                        - downY;

                        if (Math.abs(dx)
                                    > MOVE_SLOP
                                || Math.abs(dy)
                                    > MOVE_SLOP) {

                            moved = true;
                        }
                    }

                    return true;

                case MotionEvent.ACTION_UP:

                    float upX =
                            event.getX();

                    float upY =
                            event.getY();

                    long duration =
                            event.getEventTime()
                                    - downTime;

                    if (!moved) {

                        session.tap(
                                upX,
                                upY);

                    } else {

                        duration =
                                Math.max(
                                        SWIPE_MIN_DURATION,
                                        Math.min(
                                                SWIPE_MAX_DURATION,
                                                duration));

                        session.swipe(
                                downX,
                                downY,
                                upX,
                                upY,
                                duration);
                    }

                    return true;

                case MotionEvent.ACTION_CANCEL:

                    moved = false;

                    return true;

                default:

                    return true;
            }
        };
    }
}
