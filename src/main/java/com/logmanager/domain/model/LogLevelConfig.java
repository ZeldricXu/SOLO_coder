package com.logmanager.domain.model;

import com.logmanager.common.enums.LogLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
public class LogLevelConfig extends BaseEntity {
    private String serviceName;
    private String loggerName;
    private LogLevel currentLevel;
    private LogLevel targetLevel;
    private Instant effectiveAt;
    private Instant expiresAt;
    private String reason;
    private String operator;
    private Boolean active;
}
