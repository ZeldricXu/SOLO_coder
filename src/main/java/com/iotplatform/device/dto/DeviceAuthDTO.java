package com.iotplatform.device.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeviceAuthDTO {

    @NotBlank(message = "设备ID不能为空")
    private String deviceId;

    private String authToken;

    private String authSecret;

    private String signature;

    private Long timestamp;
}
