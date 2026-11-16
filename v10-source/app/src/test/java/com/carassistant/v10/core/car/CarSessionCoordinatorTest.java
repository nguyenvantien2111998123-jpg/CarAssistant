package com.carassistant.v10.core.car;

import org.junit.Test;
import static org.junit.Assert.*;

public class CarSessionCoordinatorTest {
    @Test public void connectionDoesNotStartSessionOrAttachDisplay() {
        FakeMonitor monitor = new FakeMonitor();
        FakeBackend backend = new FakeBackend();
        CarSessionCoordinator coordinator = new CarSessionCoordinator(monitor, backend);
        coordinator.start();
        monitor.emit(CarConnectionState.CONNECTED);
        CarSessionCoordinator.Snapshot state = coordinator.snapshot();
        assertEquals(CarConnectionState.CONNECTED, state.connectionState);
        assertFalse(state.sessionActive);
        assertFalse(state.displayAttached);
        assertEquals(0, backend.attachCount);
    }

    @Test public void carSessionAttachesBackendAndRoutesCommands() {
        FakeBackend backend = new FakeBackend();
        CarSessionCoordinator coordinator = new CarSessionCoordinator(new FakeMonitor(), backend);
        assertTrue(coordinator.onCarSessionStarted());
        assertTrue(coordinator.launchApp("com.example.maps", "MainActivity"));
        assertTrue(coordinator.showHome());
        assertEquals(1, backend.attachCount);
        assertEquals(1, backend.launchCount);
        assertEquals(1, backend.homeCount);
    }

    @Test public void disconnectSignalDoesNotEndLiveCarActivitySession() {
        FakeMonitor monitor = new FakeMonitor();
        FakeBackend backend = new FakeBackend();
        CarSessionCoordinator coordinator = new CarSessionCoordinator(monitor, backend);
        coordinator.start();
        assertTrue(coordinator.onCarSessionStarted());
        monitor.emit(CarConnectionState.DISCONNECTED);
        assertTrue(coordinator.snapshot().sessionActive);
        assertTrue(coordinator.snapshot().displayAttached);
        assertEquals(0, backend.detachCount);
        assertTrue(coordinator.showHome());
        coordinator.onCarSessionStopped();
        assertFalse(coordinator.snapshot().sessionActive);
        assertFalse(coordinator.snapshot().displayAttached);
        assertEquals(1, backend.detachCount);
    }

    @Test public void unavailableBackendCanBeRetriedOnSessionStart() {
        FakeBackend backend = new FakeBackend();
        backend.available = false;
        CarSessionCoordinator coordinator = new CarSessionCoordinator(new FakeMonitor(), backend);
        assertFalse(coordinator.onCarSessionStarted());
        assertFalse(coordinator.snapshot().displayAttached);
        backend.available = true;
        assertTrue(coordinator.onCarSessionStarted());
        assertTrue(coordinator.snapshot().displayAttached);
        assertEquals(1, backend.attachCount);
    }

    @Test public void startAndStopAreIdempotent() {
        FakeMonitor monitor = new FakeMonitor();
        FakeBackend backend = new FakeBackend();
        CarSessionCoordinator coordinator = new CarSessionCoordinator(monitor, backend);
        coordinator.start();
        coordinator.start();
        coordinator.onCarSessionStarted();
        coordinator.stop();
        coordinator.stop();
        assertEquals(1, monitor.startCount);
        assertEquals(1, monitor.stopCount);
        assertFalse(coordinator.snapshot().monitorStarted);
        assertFalse(coordinator.snapshot().sessionActive);
        assertEquals(1, backend.detachCount);
    }

    @Test public void backendCallsAreNotMadeWhenSessionIsInactive() {
        FakeBackend backend = new FakeBackend();
        CarSessionCoordinator coordinator = new CarSessionCoordinator(new FakeMonitor(), backend);
        assertFalse(coordinator.launchApp("com.example.maps", "MainActivity"));
        assertFalse(coordinator.showHome());
        assertEquals(0, backend.launchCount);
        assertEquals(0, backend.homeCount);
    }

    private static final class FakeMonitor implements CarConnectionMonitor {
        Listener listener;
        int startCount;
        int stopCount;
        @Override public void start(Listener value) { listener = value; startCount++; }
        @Override public void stop() { stopCount++; listener = null; }
        void emit(CarConnectionState state) { if (listener != null) listener.onConnectionChanged(state); }
    }

    private static final class FakeBackend implements CarDisplayBackend {
        boolean available = true;
        int attachCount, detachCount, launchCount, homeCount;
        @Override public String getId() { return "fake"; }
        @Override public boolean isAvailable() { return available; }
        @Override public boolean attach() { attachCount++; return available; }
        @Override public void detach() { detachCount++; }
        @Override public boolean launchApp(String pkg, String activity) { launchCount++; return true; }
        @Override public boolean showHome() { homeCount++; return true; }
    }
}