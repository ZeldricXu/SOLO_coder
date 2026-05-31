package com.taskflow.core.task.domain;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行上下文 - 领域模型
 */
@Data
@Builder
public class ExecutionContext {
    private String traceId;
    private String tenantId;
    private String userId;
    private String runId;
    private String taskId;
    private LocalDateTime startTime;
    private Map<String, Object> attributes;
    private int retryCount;
    private int maxRetries;

    private transient Map<String, Object> contextData;

    public Map<String, Object> getContextData() {
        if (contextData == null) {
            contextData = new ConcurrentHashMap<>();
        }
        return contextData;
    }

    public void setAttribute(String key, Object value) {
        getContextData().put(key, value);
    }

    public Object getAttribute(String key) {
        return getContextData().get(key);
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    public void incrementRetry() {
        this.retryCount++;
    }

    public long getElapsedMs() {
        return java.time.Duration.between(startTime, LocalDateTime.now()).toMillis();
    }
}
