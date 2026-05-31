package com.device.platform.dto;

import com.device.platform.common.DeviceStatus;
import lombok.Data;
import java.time.Instant;

@Data
public class DeviceStatusUpdateRequest {
    private DeviceStatus status;
    private String firmwareVersion;
    private String ipAddress;
    private Instant lastHeartbeatAt;
}
