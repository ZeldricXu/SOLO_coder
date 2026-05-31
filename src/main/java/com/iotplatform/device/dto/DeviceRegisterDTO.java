package com.iotplatform.device.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Map;

@Data
public class DeviceRegisterDTO {

    @NotBlank(message = "设备ID不能为空")
    private String deviceId;

    private String deviceName;

    @NotBlank(message = "设备类型不能为空")
    private String deviceType;

    @NotBlank(message = "协议类型不能为空")
    private String protocolType;

    private String authToken;

    private String authSecret;

    private Map<String, Object> metadata;

    private String createdBy;
}
