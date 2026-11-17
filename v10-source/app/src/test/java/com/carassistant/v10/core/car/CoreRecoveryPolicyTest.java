package com.carassistant.v10.core.car;

import org.junit.Test;

import static org.junit.Assert.*;

public class CoreRecoveryPolicyTest {
    @Test public void firstInstallIsNotReportedAsRecovery() {
        assertEquals(CoreRecoveryPolicy.RecoveryReason.FIRST_START,
                CoreRecoveryPolicy.classify(false, 0L, 1_700_000_000_000L));
    }

    @Test public void sameBootProcessRestartIsDetected() {
        assertEquals(CoreRecoveryPolicy.RecoveryReason.PROCESS_RESTART,
                CoreRecoveryPolicy.classify(true, 1_700_000_000_000L,
                        1_700_000_030_000L));
    }

    @Test public void changedBootMarkerIsDetectedAsDeviceReboot() {
        assertEquals(CoreRecoveryPolicy.RecoveryReason.DEVICE_REBOOT,
                CoreRecoveryPolicy.classify(true, 1_700_000_000_000L,
                        1_700_000_600_000L));
    }

    @Test public void missingPreviousBootMarkerFallsBackToProcessRestart() {
        assertEquals(CoreRecoveryPolicy.RecoveryReason.PROCESS_RESTART,
                CoreRecoveryPolicy.classify(true, 0L, 1_700_000_000_000L));
    }

    @Test public void sameBootProcessRestartPreservesAaHostSession() {
        assertFalse(CoreRecoveryPolicy.shouldResetHostSession(
                CoreRecoveryPolicy.RecoveryReason.PROCESS_RESTART));
    }

    @Test public void deviceRebootInvalidatesAaHostSession() {
        assertTrue(CoreRecoveryPolicy.shouldResetHostSession(
                CoreRecoveryPolicy.RecoveryReason.DEVICE_REBOOT));
    }

    @Test public void firstStartHasNoPreviousAaHostSession() {
        assertTrue(CoreRecoveryPolicy.shouldResetHostSession(
                CoreRecoveryPolicy.RecoveryReason.FIRST_START));
    }

    @Test public void bootBootstrapMakesCoreCoordinatorReadyWithoutCarSession() {
        assertTrue(CoreRecoveryPolicy.isCoreReady(true));
        assertFalse(CoreRecoveryPolicy.isCoreReady(false));
    }

    @Test public void coreReadinessDoesNotDependOnPhoneOrCarActivity() {
        assertTrue(CoreRecoveryPolicy.isCoreReady(true, true));
        assertFalse(CoreRecoveryPolicy.isCoreReady(true, false));
        assertFalse(CoreRecoveryPolicy.isCoreReady(false, true));
    }

    @Test public void legacyReadinessOverloadStillRequiresAllComponents() {
        assertTrue(CoreRecoveryPolicy.isCoreReady(true, true, true));
        assertFalse(CoreRecoveryPolicy.isCoreReady(true, true, false));
        assertFalse(CoreRecoveryPolicy.isCoreReady(true, false, true));
        assertFalse(CoreRecoveryPolicy.isCoreReady(false, true, true));
    }
}
