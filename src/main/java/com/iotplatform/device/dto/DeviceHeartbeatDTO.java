package com.iotplatform.device.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Map;

@Data
public class DeviceHeartbeatDTO {

    @NotBlank(message = "设备ID不能为空")
    private String deviceId;

    private String status;

    private Long timestamp;

    private Map<String, Object> metrics;

    private String firmwareVersion;

    private Integer signalStrength;
}
