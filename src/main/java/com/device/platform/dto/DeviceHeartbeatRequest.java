package com.device.platform.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.time.Instant;

@Data
public class DeviceHeartbeatRequest {
    @NotBlank(message = "deviceId不能为空")
    @Size(min = 1, max = 128, message = "deviceId长度必须在1-128字符之间")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "deviceId格式不正确")
    private String deviceId;

    private Instant timestamp;

    @Size(max = 32, message = "firmwareVersion长度不能超过32字符")
    @Pattern(regexp = "^[a-zA-Z0-9.+-]*$", message = "firmwareVersion格式不正确")
    private String firmwareVersion;

    @Size(max = 32, message = "networkStatus长度不能超过32字符")
    private String networkStatus;

    @Min(value = 0, message = "signalStrength不能小于0")
    @Max(value = 100, message = "signalStrength不能大于100")
    private Integer signalStrength;

    @DecimalMin(value = "0.0", message = "batteryLevel不能小于0")
    @DecimalMax(value = "100.0", message = "batteryLevel不能大于100")
    private Double batteryLevel;
}
