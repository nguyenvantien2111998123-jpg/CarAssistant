package com.carassistant.v10;

import android.view.MotionEvent;
import android.view.View;

public final class V10InputController {

    private final V10ProjectionSession session;

    private float downX;
    private float downY;
    private float secondDownX;
    private float secondDownY;
    private float lastX1;
    private float lastY1;
    private float lastX2;
    private float lastY2;

    private long downTime;
    private long secondDownTime;

    private int firstPointerId = -1;
    private int secondPointerId = -1;

    private boolean moved;
    private boolean twoFingerGesture;
    private boolean gestureHandled;

    private static final float MOVE_SLOP = 12f;
    private static final long SWIPE_MIN_DURATION = 80L;
    private static final long SWIPE_MAX_DURATION = 800L;

    public V10InputController(V10ProjectionSession session) {
        this.session = session;
    }

    public View.OnTouchListener createListener() {
        return (view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX(0);
                    downY = event.getY(0);
                    lastX1 = downX;
                    lastY1 = downY;
                    downTime = event.getEventTime();
                    firstPointerId = event.getPointerId(0);
                    secondPointerId = -1;
                    moved = false;
                    twoFingerGesture = false;
                    gestureHandled = false;
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (!twoFingerGesture && event.getPointerCount() >= 2) {
                        int index = event.getActionIndex();
                        secondPointerId = event.getPointerId(index);
                        secondDownX = event.getX(index);
                        secondDownY = event.getY(index);
                        lastX2 = secondDownX;
                        lastY2 = secondDownY;
                        secondDownTime = event.getEventTime();
                        twoFingerGesture = true;
                        moved = true;
                    }
                    updateTrackedPositions(event);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    updateTrackedPositions(event);
                    if (twoFingerGesture) {
                        return true;
                    }
                    if (event.getPointerCount() == 1) {
                        float dx = event.getX(0) - downX;
                        float dy = event.getY(0) - downY;
                        if (Math.abs(dx) > MOVE_SLOP || Math.abs(dy) > MOVE_SLOP) {
                            moved = true;
                        }
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    updateTrackedPositions(event);
                    if (twoFingerGesture && !gestureHandled
                            && isTrackedPointer(event.getPointerId(event.getActionIndex()))) {
                        long duration = clampDuration(event.getEventTime() - Math.min(downTime, secondDownTime));
                        session.pinch(
                                downX, downY, lastX1, lastY1,
                                secondDownX, secondDownY, lastX2, lastY2,
                                duration);
                        gestureHandled = true;
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    updateTrackedPositions(event);
                    if (!gestureHandled && !twoFingerGesture) {
                        float upX = event.getX(0);
                        float upY = event.getY(0);
                        long duration = event.getEventTime() - downTime;
                        if (!moved) {
                            session.tap(upX, upY);
                        } else {
                            session.swipe(
                                    downX, downY, upX, upY,
                                    clampDuration(duration));
                        }
                    }
                    resetGesture();
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    resetGesture();
                    return true;

                default:
                    return true;
            }
        };
    }

    private void updateTrackedPositions(MotionEvent event) {
        int firstIndex = firstPointerId < 0 ? -1 : event.findPointerIndex(firstPointerId);
        if (firstIndex >= 0) {
            lastX1 = event.getX(firstIndex);
            lastY1 = event.getY(firstIndex);
        }

        int secondIndex = secondPointerId < 0 ? -1 : event.findPointerIndex(secondPointerId);
        if (secondIndex >= 0) {
            lastX2 = event.getX(secondIndex);
            lastY2 = event.getY(secondIndex);
        }
    }

    private boolean isTrackedPointer(int pointerId) {
        return pointerId == firstPointerId || pointerId == secondPointerId;
    }

    private static long clampDuration(long duration) {
        return Math.max(SWIPE_MIN_DURATION, Math.min(SWIPE_MAX_DURATION, duration));
    }

    private void resetGesture() {
        firstPointerId = -1;
        secondPointerId = -1;
        moved = false;
        twoFingerGesture = false;
        gestureHandled = false;
    }
}
