package com.edgescheduler.device.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class DeviceDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    @NotEmpty(message = "deviceKey cannot be empty")
    private String deviceKey;

    private String deviceName;

    @NotEmpty(message = "deviceType cannot be empty")
    private String deviceType;

    @NotEmpty(message = "productKey cannot be empty")
    private String productKey;

    private String firmwareVersion;

    private String status;

    private String authType;

    private String authSecret;

    private Map<String, Object> metadata;

    private LocalDateTime lastOnlineAt;

    private LocalDateTime activatedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
