package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("device_auth")
public class DeviceAuth extends BaseEntity {
    private String deviceId;
    private String sessionId;
    private String token;
    private String refreshToken;
    private Instant expiresAt;
    private Instant lastAuthenticatedAt;
    private String authMethod;
    private String clientIp;
    private String userAgent;
    private boolean revoked;
}
