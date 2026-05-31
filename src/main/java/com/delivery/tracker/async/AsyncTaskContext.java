package com.delivery.tracker.async;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 异步任务上下文
 * 封装任务执行过程中的所有状态和元数据
 */
@Data
@Builder
public class AsyncTaskContext {

    private String taskId;
    private String traceId;
    private String namespace;
    private Map<String, Object> params;
    private Map<String, Object> payload;

    private volatile AsyncTaskStatus status;
    private volatile Map<String, Object> result;
    private volatile String errorMessage;
    private volatile Throwable error;

    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private long timeoutMs;

    private final AtomicReference<Boolean> cancelled = new AtomicReference<>(false);

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
        this.status = AsyncTaskStatus.CANCELLED;
    }

    public boolean isTimeout() {
        if (timeoutMs <= 0 || startedAt == null) {
            return false;
        }
        return java.time.Duration.between(startedAt, LocalDateTime.now()).toMillis() > timeoutMs;
    }

    public long getElapsedMs() {
        if (startedAt == null) {
            return 0;
        }
        LocalDateTime end = completedAt != null ? completedAt : LocalDateTime.now();
        return java.time.Duration.between(startedAt, end).toMillis();
    }
}
