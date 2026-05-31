package com.device.platform.dto;

import lombok.Data;

@Data
public class DeviceDeactivateRequest {
    private String reason;
    private String operator;
}
