package com.delivery.tracker.async;

/**
 * 异步任务状态
 */
public enum AsyncTaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMEOUT
}
