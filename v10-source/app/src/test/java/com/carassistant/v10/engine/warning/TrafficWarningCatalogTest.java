package com.carassistant.v10.engine.warning;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class TrafficWarningCatalogTest {
    @Test
    public void sourceIdsMatchCarStreamSelector() {
        assertEquals(TrafficWarningSource.WYN,
                TrafficWarningSource.fromCarStreamId("wyn"));
        assertEquals(TrafficWarningSource.VIETMAP_WIDGET,
                TrafficWarningSource.fromCarStreamId("widget"));
        assertEquals(TrafficWarningSource.GOFA,
                TrafficWarningSource.fromCarStreamId("z23e037d98"));
        assertEquals(TrafficWarningSource.WAZE,
                TrafficWarningSource.fromCarStreamId("zda617cedc"));
        assertEquals(TrafficWarningSource.WAZE_HLP,
                TrafficWarningSource.fromCarStreamId("z9e4343e4b"));
        assertNull(TrafficWarningSource.fromCarStreamId("carstream"));
    }

    @Test
    public void everyRecoveredCarStreamWarningIdHasAMapping() {
        String[] ids = {
                "warningAlertCameraAIId",
                "warningAlertHighwayRestAreaId",
                "warningAlertNationalHighwayRestAreaId",
                "warningAlertNoParkNoStopId",
                "warningAlertNoParkingId",
                "warningAlertNoParkingEvenId",
                "warningAlertNoParkingOddId",
                "warningAlertNoPassingGoingInId",
                "warningAlertNoPassingGoingOutId",
                "warningAlertPenaltyCameraId",
                "warningAlertRailwayId",
                "warningAlertResidentialAreaGoingInId",
                "warningAlertResidentialAreaGoingOutId",
                "warningAlertSpeedLimitCameraId",
                "warningAlertSpeedLimitCheckpointId",
                "warningAlertTollFeeETCId",
                "warningAlertTollFeeNonETCId",
                "warningAlertTrafficCameraId",
                "warningAlertTunnelId"
        };

        assertEquals(19, ids.length);
        for (String id : ids) {
            assertNotNull("Missing warning mapping for " + id,
                    TrafficWarningType.fromCarStreamId(id));
            assertTrue(TrafficWarningType.fromCarStreamId(id).isTemporary());
        }
    }

    @Test
    public void speedLimitIsPersistentAndUnknownIdsAreRejected() {
        TrafficWarningType speed = TrafficWarningType.fromCarStreamId("speed_limit");
        assertNotNull(speed);
        assertFalse(speed.isTemporary());
        assertNull(TrafficWarningType.fromCarStreamId("warningAlertNotInCarStream"));
    }

    @Test
    public void carStreamItselfIsNotASelectableCarAsistantSource() {
        assertEquals(5, TrafficWarningSource.values().length);
        for (TrafficWarningSource source : TrafficWarningSource.values()) {
            assertFalse("carstream".equals(source.getCarStreamId()));
        }
    }
}
