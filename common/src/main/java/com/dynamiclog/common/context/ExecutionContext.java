package com.dynamiclog.common.context;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
public class ExecutionContext {
    private String traceId;
    private String userId;
    private String namespace;
    private LocalDateTime startTime;
    private Map<String, Object> attributes = new HashMap<>();
    private boolean timeoutOccurred;
    private String errorMessage;

    public ExecutionContext() {
        this.startTime = LocalDateTime.now();
    }

    public long getElapsedMs() {
        return java.time.Duration.between(startTime, LocalDateTime.now()).toMillis();
    }
}
