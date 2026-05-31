package com.device.platform.dto;

import lombok.Data;
import java.time.Instant;

@Data
public class DeviceAuthResponse {
    private String deviceId;
    private String token;
    private String refreshToken;
    private Instant expiresAt;
    private String sessionId;
}
