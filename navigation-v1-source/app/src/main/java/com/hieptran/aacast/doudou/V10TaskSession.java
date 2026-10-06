package com.carassistant.v10;

import android.content.ComponentName;

public final class V10TaskSession {
    public enum State { NEW, ACTIVE, HIDDEN, CLOSED }

    private ComponentName target;
    private State state = State.NEW;
    private long lastActiveTime;

    public synchronized boolean setTarget(ComponentName component) {
        if (component == null) return false;
        boolean changed = target == null || !component.equals(target);
        if (changed) {
            target = component;
            state = State.NEW;
        }
        return changed;
    }

    public synchronized ComponentName getTarget() { return target; }
    public synchronized State getState() { return state; }
    public synchronized boolean hasTarget() { return target != null; }

    public synchronized void active() {
        state = State.ACTIVE;
        lastActiveTime = System.currentTimeMillis();
    }

    public synchronized void hidden() {
        if (target != null) state = State.HIDDEN;
    }

    public synchronized void closed() {
        state = State.CLOSED;
    }

    public synchronized long getLastActiveTime() { return lastActiveTime; }

    public synchronized void clear() {
        target = null;
        state = State.CLOSED;
        lastActiveTime = 0L;
    }
}
