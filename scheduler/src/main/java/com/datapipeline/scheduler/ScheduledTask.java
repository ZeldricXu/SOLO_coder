package com.datapipeline.scheduler;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledTask {

    public enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
        TIMED_OUT
    }

    public enum Type {
        ONE_TIME,
        FIXED_RATE,
        FIXED_DELAY,
        CRON
    }

    private String taskId;
    private String name;
    private Type type;
    private Runnable task;
    private Map<String, Object> metadata;

    private Status status;
    private Instant scheduledAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant nextExecutionAt;

    private Duration initialDelay;
    private Duration period;
    private String cronExpression;

    @Builder.Default
    private AtomicInteger executionCount = new AtomicInteger(0);
    @Builder.Default
    private AtomicInteger failureCount = new AtomicInteger(0);
    private int maxRetries;
    private Duration timeout;

    private String lastError;
    private Throwable lastException;

    public void markRunning() {
        this.status = Status.RUNNING;
        this.startedAt = Instant.now();
        this.executionCount.incrementAndGet();
    }

    public void markCompleted() {
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void markFailed(String error, Throwable exception) {
        this.status = Status.FAILED;
        this.completedAt = Instant.now();
        this.lastError = error;
        this.lastException = exception;
        this.failureCount.incrementAndGet();
    }

    public void markCancelled() {
        this.status = Status.CANCELLED;
        this.completedAt = Instant.now();
    }

    public void markTimedOut() {
        this.status = Status.TIMED_OUT;
        this.completedAt = Instant.now();
        this.failureCount.incrementAndGet();
    }

    public boolean shouldRetry() {
        return failureCount.get() < maxRetries;
    }

    public long getDurationMs() {
        if (startedAt == null) {
            return 0;
        }
        Instant end = completedAt != null ? completedAt : Instant.now();
        return Duration.between(startedAt, end).toMillis();
    }

}
