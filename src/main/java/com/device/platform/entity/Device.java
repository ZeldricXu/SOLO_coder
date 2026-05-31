package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.device.platform.common.DeviceStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("device")
public class Device extends BaseEntity {
    private String deviceId;
    private String deviceName;
    private String deviceType;
    private String productKey;
    private String deviceSecret;
    private DeviceStatus status;
    private String firmwareVersion;
    private String hardwareVersion;
    private String ipAddress;
    private String region;
    private Instant lastHeartbeatAt;
    private Instant activatedAt;
    private Instant deactivatedAt;
    private String attributes;
    private String tags;
}
