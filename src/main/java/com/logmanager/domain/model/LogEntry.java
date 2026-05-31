package com.logmanager.domain.model;

import com.logmanager.common.enums.LogLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class LogEntry extends BaseEntity {
    private String traceId;
    private String serviceName;
    private LogLevel level;
    private String message;
    private String loggerName;
    private String threadName;
    private Instant timestamp;
    private Map<String, String> tags = new HashMap<>();
    private Map<String, Object> metadata = new HashMap<>();
}
