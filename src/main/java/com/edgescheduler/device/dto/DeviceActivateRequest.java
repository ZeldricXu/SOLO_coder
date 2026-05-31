package com.edgescheduler.device.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class DeviceActivateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotEmpty(message = "deviceKey cannot be empty")
    private String deviceKey;

    @NotEmpty(message = "productKey cannot be empty")
    private String productKey;

    private String authSecret;

    private String firmwareVersion;

    private Map<String, Object> metadata;
}
