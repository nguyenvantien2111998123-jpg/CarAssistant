package com.carassistant.v10;

import android.view.MotionEvent;
import android.view.View;

/** Maps TextureView touch gestures to the projected display. */
public final class V10InputController {

    private final V10ProjectionSession session;

    private float downX;
    private float downY;
    private long downTime;
    private boolean moved;

    private int firstPointerId = -1;
    private int secondPointerId = -1;
    private boolean pinching;
    private boolean pinchFinished;
    private long lastPinchMoveTime;
    private float lastX0;
    private float lastY0;
    private float lastX1;
    private float lastY1;

    private static final float MOVE_SLOP = 12f;
    private static final long SWIPE_MIN_DURATION = 80L;
    private static final long SWIPE_MAX_DURATION = 800L;
    private static final long PINCH_MOVE_INTERVAL_MS = 24L;

    public V10InputController(V10ProjectionSession session) {
        this.session = session;
    }

    public View.OnTouchListener createListener() {
        return (view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    downTime = event.getEventTime();
                    moved = false;
                    pinching = false;
                    pinchFinished = false;
                    firstPointerId = event.getPointerId(0);
                    secondPointerId = -1;
                    lastX0 = downX;
                    lastY0 = downY;
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (!pinching && !pinchFinished && event.getPointerCount() >= 2) {
                        int actionIndex = event.getActionIndex();
                        int firstIndex = event.findPointerIndex(firstPointerId);
                        int secondIndex = actionIndex;
                        if (firstIndex < 0 || secondIndex == firstIndex) {
                            secondIndex = firstIndex == 0 ? 1 : 0;
                        }
                        if (firstIndex >= 0 && secondIndex >= 0
                                && secondIndex < event.getPointerCount()
                                && secondIndex != firstIndex) {
                            secondPointerId = event.getPointerId(secondIndex);
                            lastX0 = event.getX(firstIndex);
                            lastY0 = event.getY(firstIndex);
                            lastX1 = event.getX(secondIndex);
                            lastY1 = event.getY(secondIndex);
                            session.multiTouchStart(lastX0, lastY0, lastX1, lastY1);
                            pinching = true;
                            moved = true;
                            lastPinchMoveTime = event.getEventTime();
                        }
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (pinching && event.getPointerCount() >= 2) {
                        int index0 = event.findPointerIndex(firstPointerId);
                        int index1 = event.findPointerIndex(secondPointerId);
                        if (index0 >= 0 && index1 >= 0) {
                            lastX0 = event.getX(index0);
                            lastY0 = event.getY(index0);
                            lastX1 = event.getX(index1);
                            lastY1 = event.getY(index1);
                            long eventTime = event.getEventTime();
                            if (eventTime - lastPinchMoveTime >= PINCH_MOVE_INTERVAL_MS) {
                                session.multiTouchMove(lastX0, lastY0, lastX1, lastY1);
                                lastPinchMoveTime = eventTime;
                            }
                        }
                    } else if (!pinchFinished && event.getPointerCount() == 1) {
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        if (Math.abs(dx) > MOVE_SLOP || Math.abs(dy) > MOVE_SLOP) {
                            moved = true;
                        }
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    if (pinching) {
                        int liftedPointerId = event.getPointerId(event.getActionIndex());
                        if (liftedPointerId != firstPointerId && liftedPointerId != secondPointerId) {
                            return true;
                        }
                        int index0 = event.findPointerIndex(firstPointerId);
                        int index1 = event.findPointerIndex(secondPointerId);
                        if (index0 >= 0 && index1 >= 0) {
                            lastX0 = event.getX(index0);
                            lastY0 = event.getY(index0);
                            lastX1 = event.getX(index1);
                            lastY1 = event.getY(index1);
                        }
                        session.multiTouchEnd(lastX0, lastY0, lastX1, lastY1);
                        pinching = false;
                        pinchFinished = true;
                        moved = true;
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (pinching) {
                        session.multiTouchEnd(lastX0, lastY0, lastX1, lastY1);
                        pinching = false;
                        pinchFinished = true;
                        return true;
                    }
                    if (pinchFinished) {
                        resetGesture();
                        return true;
                    }

                    float upX = event.getX();
                    float upY = event.getY();
                    long duration = event.getEventTime() - downTime;
                    if (!moved) {
                        session.tap(upX, upY);
                    } else {
                        duration = Math.max(SWIPE_MIN_DURATION,
                                Math.min(SWIPE_MAX_DURATION, duration));
                        session.swipe(downX, downY, upX, upY, duration);
                    }
                    resetGesture();
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    if (pinching) {
                        session.multiTouchCancel(lastX0, lastY0, lastX1, lastY1);
                    }
                    resetGesture();
                    return true;

                default:
                    return true;
            }
        };
    }

    private void resetGesture() {
        moved = false;
        pinching = false;
        pinchFinished = false;
        firstPointerId = -1;
        secondPointerId = -1;
    }
}
