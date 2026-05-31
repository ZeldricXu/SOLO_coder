package com.device.platform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeviceAuthRequest {
    @NotBlank(message = "deviceId不能为空")
    private String deviceId;

    @NotBlank(message = "deviceSecret不能为空")
    private String deviceSecret;

    private String authMethod;
    private String clientIp;
    private String userAgent;
}
