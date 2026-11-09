package com.carassistant.v10.engine.warning;

/**
 * Warning identifiers used by CarStream's HUD renderer (Lj2.g) and Vietnamese
 * localization bundle. The identifiers are preserved so source adapters can
 * normalize their payloads without depending on CarStream's obfuscated classes.
 */
public enum TrafficWarningType {
    SPEED_LIMIT("speed_limit", "Giới hạn tốc độ", false),

    CAMERA_AI("warningAlertCameraAIId", "Camera AI", true),
    HIGHWAY_REST_AREA("warningAlertHighwayRestAreaId", "Trạm dừng nghỉ", true),
    NATIONAL_HIGHWAY_REST_AREA("warningAlertNationalHighwayRestAreaId", "Trạm dừng nghỉ quốc lộ", true),
    NO_PARK_NO_STOP("warningAlertNoParkNoStopId", "Cấm dừng đỗ", true),
    NO_PARKING("warningAlertNoParkingId", "Cấm đỗ", true),
    NO_PARKING_EVEN("warningAlertNoParkingEvenId", "Cấm đỗ ngày chẵn", true),
    NO_PARKING_ODD("warningAlertNoParkingOddId", "Cấm đỗ ngày lẻ", true),
    NO_PASSING_GOING_IN("warningAlertNoPassingGoingInId", "Bắt đầu cấm vượt", true),
    NO_PASSING_GOING_OUT("warningAlertNoPassingGoingOutId", "Hết cấm vượt", true),
    PENALTY_CAMERA("warningAlertPenaltyCameraId", "Camera phạt nguội", true),
    RAILWAY("warningAlertRailwayId", "Đường sắt", true),
    RESIDENTIAL_AREA_GOING_IN("warningAlertResidentialAreaGoingInId", "Vào khu dân cư", true),
    RESIDENTIAL_AREA_GOING_OUT("warningAlertResidentialAreaGoingOutId", "Ra khỏi khu dân cư", true),
    SPEED_LIMIT_CAMERA("warningAlertSpeedLimitCameraId", "Camera tốc độ", true),
    SPEED_LIMIT_CHECKPOINT("warningAlertSpeedLimitCheckpointId", "Trạm kiểm tra tốc độ", true),
    TOLL_FEE_ETC("warningAlertTollFeeETCId", "Thu phí ETC", true),
    TOLL_FEE_NON_ETC("warningAlertTollFeeNonETCId", "Trạm thu phí", true),
    TRAFFIC_CAMERA("warningAlertTrafficCameraId", "Camera giao thông", true),
    TUNNEL("warningAlertTunnelId", "Hầm đường bộ", true);

    private final String carStreamId;
    private final String vietnameseLabel;
    private final boolean temporary;

    TrafficWarningType(String carStreamId, String vietnameseLabel, boolean temporary) {
        this.carStreamId = carStreamId;
        this.vietnameseLabel = vietnameseLabel;
        this.temporary = temporary;
    }

    public String getCarStreamId() {
        return carStreamId;
    }

    public String getVietnameseLabel() {
        return vietnameseLabel;
    }

    public boolean isTemporary() {
        return temporary;
    }

    public static TrafficWarningType fromCarStreamId(String id) {
        if (id == null) return null;
        for (TrafficWarningType type : values()) {
            if (type.carStreamId.equals(id)) return type;
        }
        return null;
    }
}
