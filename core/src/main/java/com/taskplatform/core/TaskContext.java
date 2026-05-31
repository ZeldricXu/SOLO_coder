package com.taskplatform.core;

import com.taskplatform.common.util.ContextHolder;
import com.taskplatform.common.util.PerformanceUtils;
import com.taskplatform.persistence.entity.Task;
import com.taskplatform.persistence.entity.TaskRun;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Data
public class TaskContext implements AutoCloseable {

    private static final int INITIAL_CAPACITY = 4;

    private String traceId;
    private Task task;
    private TaskRun taskRun;
    private LocalDateTime startTime;
    private long timeoutMs;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>(INITIAL_CAPACITY);
    private Semaphore resourceSemaphore;
    private boolean resourceAcquired;
    private CompletableFuture<?> executionFuture;
    private volatile boolean cancelled;
    private volatile boolean completed;
    private Object result;
    private Throwable error;

    public TaskContext(Task task) {
        this.traceId = ContextHolder.getTraceId();
        this.task = task;
        this.startTime = LocalDateTime.now();
        this.timeoutMs = task.getTimeoutSeconds() != null ?
                task.getTimeoutSeconds() * 1000L : 300000L;
    }

    public boolean isTimedOut() {
        return PerformanceUtils.hasExceededTimeout(startTime, timeoutMs);
    }

    public long getRemainingTimeMs() {
        return PerformanceUtils.remainingTimeMs(startTime, timeoutMs);
    }

    public long getElapsedMs() {
        return PerformanceUtils.currentElapsedMs(startTime);
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public <T> T getAttribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return null;
    }

    @Override
    public void close() {
        if (resourceSemaphore != null && resourceAcquired) {
            resourceSemaphore.release();
            resourceAcquired = false;
        }
        ContextHolder.clear();
    }
}
