package com.device.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.Map;

@Data
public class DeviceShadowUpdateRequest {
    @NotBlank(message = "deviceId不能为空")
    private String deviceId;

    @NotNull(message = "state不能为空")
    private Map<String, Object> state;

    private Integer version;
}
