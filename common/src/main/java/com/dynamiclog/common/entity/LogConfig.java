package com.dynamiclog.common.entity;

import com.dynamiclog.common.enums.LogLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class LogConfig extends BaseEntity {
    private String loggerName;
    private LogLevel level;
    private LogLevel effectiveLevel;
    private String namespace;
    private String appName;
    private String instanceId;
    private Boolean dynamicEnabled;
    private Long ttlSeconds;
    private LocalDateTime expiresAt;
}
