package com.healthtrack.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

public class AnalysisTask implements Serializable {

    private String taskId;
    private String userId;
    private String dataType;
    private Double currentValue;
    private int retryCount;
    private int maxRetry;
    private LocalDateTime createdAt;
    private TaskStatus status;

    public enum TaskStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    public AnalysisTask() {
        this.taskId = "analysis_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.retryCount = 0;
        this.maxRetry = 3;
        this.createdAt = LocalDateTime.now();
        this.status = TaskStatus.PENDING;
    }

    public AnalysisTask(String userId, String dataType, Double currentValue) {
        this();
        this.userId = userId;
        this.dataType = dataType;
        this.currentValue = currentValue;
    }

    public boolean canRetry() {
        return this.retryCount < this.maxRetry;
    }

    public void incrementRetry() {
        this.retryCount++;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public Double getCurrentValue() { return currentValue; }
    public void setCurrentValue(Double currentValue) { this.currentValue = currentValue; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public int getMaxRetry() { return maxRetry; }
    public void setMaxRetry(int maxRetry) { this.maxRetry = maxRetry; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
}
