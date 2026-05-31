package com.device.platform.dto;

import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
public class DeviceShadowResponse {
    private String deviceId;
    private Map<String, Object> desiredState;
    private Map<String, Object> reportedState;
    private Map<String, Object> deltaState;
    private Integer version;
    private Instant desiredUpdatedAt;
    private Instant reportedUpdatedAt;
    private boolean syncPending;
}
