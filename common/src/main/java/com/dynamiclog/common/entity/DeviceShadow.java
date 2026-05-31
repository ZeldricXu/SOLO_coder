package com.dynamiclog.common.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class DeviceShadow extends BaseEntity {
    private String deviceId;
    private String deviceName;
    private String deviceType;
    private Map<String, Object> desiredState;
    private Map<String, Object> reportedState;
    private Map<String, Object> deltaState;
    private Integer version;
    private LocalDateTime lastReportedAt;
    private LocalDateTime lastDesiredUpdatedAt;
    private Boolean online;
    private String connectionStatus;
    private String namespace;
}
