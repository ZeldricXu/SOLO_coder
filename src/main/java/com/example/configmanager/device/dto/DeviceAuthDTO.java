package com.example.configmanager.device.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class DeviceAuthDTO implements Serializable {

    @NotBlank(message = "设备ID不能为空")
    private String deviceId;

    @NotBlank(message = "认证令牌不能为空")
    private String authToken;
}
