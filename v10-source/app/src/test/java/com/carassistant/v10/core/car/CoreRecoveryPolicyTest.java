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

    @Test public void coreIsReadyOnlyWhenAllRequiredComponentsAreReady() {
        assertTrue(CoreRecoveryPolicy.isCoreReady(true, true, true));
        assertFalse(CoreRecoveryPolicy.isCoreReady(true, true, false));
        assertFalse(CoreRecoveryPolicy.isCoreReady(true, false, true));
        assertFalse(CoreRecoveryPolicy.isCoreReady(false, true, true));
    }
}
