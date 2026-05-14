package com.datasync.model;

import com.datasync.common.Constants;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class RetryRecord {

    @JsonProperty("retry_id")
    private String retryId;

    @JsonProperty("sync_id")
    private String syncId;

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("retry_count")
    private int retryCount;

    @JsonProperty("max_retries")
    private int maxRetries;

    @JsonProperty("next_retry_time")
    private Long nextRetryTime;

    @JsonProperty("status")
    private String status;

    @JsonProperty("errors")
    private List<String> errors;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("last_attempt_at")
    private Instant lastAttemptAt;

    @JsonProperty("completed_at")
    private Instant completedAt;

    @JsonProperty("successful")
    private Boolean successful;

    public RetryRecord() {
        this.retryId = "retry_" + UUID.randomUUID().toString().substring(0, 8);
        this.createdAt = Instant.now();
        this.retryCount = 0;
        this.status = "pending";
        this.errors = new ArrayList<>();
        this.successful = false;
        this.nextRetryTime = System.currentTimeMillis() + Constants.DEFAULT_RETRY_INTERVAL;
    }

    public String getRetryId() {
        return retryId;
    }

    public void setRetryId(String retryId) {
        this.retryId = retryId;
    }

    public String getSyncId() {
        return syncId;
    }

    public void setSyncId(String syncId) {
        this.syncId = syncId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Long getNextRetryTime() {
        return nextRetryTime;
    }

    public void setNextRetryTime(Long nextRetryTime) {
        this.nextRetryTime = nextRetryTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Boolean getSuccessful() {
        return successful;
    }

    public void setSuccessful(Boolean successful) {
        this.successful = successful;
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    public void incrementRetryCount() {
        this.retryCount++;
        this.lastAttemptAt = Instant.now();
    }

    public long calculateNextRetryDelay() {
        return Constants.DEFAULT_RETRY_INTERVAL * (retryCount + 1);
    }

    public void scheduleNextRetry() {
        this.nextRetryTime = System.currentTimeMillis() + calculateNextRetryDelay();
    }

    public void markCompleted(boolean success) {
        this.successful = success;
        this.completedAt = Instant.now();
        this.status = success ? "success" : "failed";
    }

    public void markExhausted() {
        this.successful = false;
        this.completedAt = Instant.now();
        this.status = "exhausted";
    }

    public void addError(String error) {
        if (this.errors == null) {
            this.errors = new ArrayList<>();
        }
        this.errors.add(error);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RetryRecord that = (RetryRecord) o;
        return Objects.equals(retryId, that.retryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(retryId);
    }

    @Override
    public String toString() {
        return "RetryRecord{" +
                "retryId='" + retryId + '\'' +
                ", syncId='" + syncId + '\'' +
                ", taskId='" + taskId + '\'' +
                ", retryCount=" + retryCount +
                ", maxRetries=" + maxRetries +
                ", status='" + status + '\'' +
                '}';
    }
}
