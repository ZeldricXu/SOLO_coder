package com.datasync.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class RetryFailureDetail {

    @JsonProperty("detail_id")
    private String detailId;

    @JsonProperty("retry_id")
    private String retryId;

    @JsonProperty("sync_id")
    private String syncId;

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("failure_type")
    private String failureType;

    @JsonProperty("error_code")
    private String errorCode;

    @JsonProperty("error_message")
    private String errorMessage;

    @JsonProperty("stack_trace")
    private String stackTrace;

    @JsonProperty("data_key")
    private String dataKey;

    @JsonProperty("data_snapshot")
    private Map<String, Object> dataSnapshot;

    @JsonProperty("context")
    private Map<String, Object> context;

    @JsonProperty("failed_at")
    private Instant failedAt;

    @JsonProperty("retry_attempt")
    private Integer retryAttempt;

    @JsonProperty("retry_config")
    private Map<String, Object> retryConfig;

    @JsonProperty("resolution_status")
    private String resolutionStatus;

    @JsonProperty("resolved_at")
    private Instant resolvedAt;

    @JsonProperty("resolution_notes")
    private String resolutionNotes;

    @JsonProperty("tags")
    private List<String> tags;

    public RetryFailureDetail() {
        this.detailId = "rf_" + UUID.randomUUID().toString().substring(0, 12);
        this.failedAt = Instant.now();
        this.resolutionStatus = "unresolved";
        this.context = new HashMap<>();
        this.tags = new ArrayList<>();
        this.retryConfig = new HashMap<>();
    }

    public String getDetailId() {
        return detailId;
    }

    public void setDetailId(String detailId) {
        this.detailId = detailId;
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

    public String getFailureType() {
        return failureType;
    }

    public void setFailureType(String failureType) {
        this.failureType = failureType;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    public String getDataKey() {
        return dataKey;
    }

    public void setDataKey(String dataKey) {
        this.dataKey = dataKey;
    }

    public Map<String, Object> getDataSnapshot() {
        return dataSnapshot;
    }

    public void setDataSnapshot(Map<String, Object> dataSnapshot) {
        this.dataSnapshot = dataSnapshot;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(Instant failedAt) {
        this.failedAt = failedAt;
    }

    public Integer getRetryAttempt() {
        return retryAttempt;
    }

    public void setRetryAttempt(Integer retryAttempt) {
        this.retryAttempt = retryAttempt;
    }

    public Map<String, Object> getRetryConfig() {
        return retryConfig;
    }

    public void setRetryConfig(Map<String, Object> retryConfig) {
        this.retryConfig = retryConfig;
    }

    public String getResolutionStatus() {
        return resolutionStatus;
    }

    public void setResolutionStatus(String resolutionStatus) {
        this.resolutionStatus = resolutionStatus;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getResolutionNotes() {
        return resolutionNotes;
    }

    public void setResolutionNotes(String resolutionNotes) {
        this.resolutionNotes = resolutionNotes;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public void addTag(String tag) {
        if (this.tags == null) {
            this.tags = new ArrayList<>();
        }
        this.tags.add(tag);
    }

    public void addContext(String key, Object value) {
        if (this.context == null) {
            this.context = new HashMap<>();
        }
        this.context.put(key, value);
    }

    public void markResolved(String notes) {
        this.resolutionStatus = "resolved";
        this.resolvedAt = Instant.now();
        this.resolutionNotes = notes;
    }

    public void markIgnored(String notes) {
        this.resolutionStatus = "ignored";
        this.resolvedAt = Instant.now();
        this.resolutionNotes = notes;
    }

    public static RetryFailureDetail fromException(
            String taskId, String syncId, String retryId,
            int attempt, Throwable exception,
            String dataKey, Map<String, Object> dataSnapshot) {

        RetryFailureDetail detail = new RetryFailureDetail();
        detail.setTaskId(taskId);
        detail.setSyncId(syncId);
        detail.setRetryId(retryId);
        detail.setRetryAttempt(attempt);
        detail.setDataKey(dataKey);
        detail.setDataSnapshot(dataSnapshot);

        if (exception != null) {
            detail.setErrorMessage(exception.getMessage());
            detail.setFailureType(determineFailureType(exception));
            detail.setErrorCode(determineErrorCode(exception));

            StringBuilder stackTraceBuilder = new StringBuilder();
            stackTraceBuilder.append(exception.getClass().getName())
                    .append(": ")
                    .append(exception.getMessage())
                    .append("\n");

            int lineCount = 0;
            for (StackTraceElement element : exception.getStackTrace()) {
                if (lineCount >= 20) {
                    stackTraceBuilder.append("  ... (stack trace truncated)");
                    break;
                }
                stackTraceBuilder.append("  at ")
                        .append(element.toString())
                        .append("\n");
                lineCount++;
            }
            detail.setStackTrace(stackTraceBuilder.toString());
        }

        return detail;
    }

    private static String determineFailureType(Throwable exception) {
        String className = exception.getClass().getName().toLowerCase();

        if (className.contains("connection") || className.contains("socket") ||
            className.contains("timeout") || className.contains("connect")) {
            return "connection_error";
        }
        if (className.contains("sql") || className.contains("database")) {
            return "database_error";
        }
        if (className.contains("serialization") || className.contains("json")) {
            return "serialization_error";
        }
        if (className.contains("nullpointer")) {
            return "null_pointer_error";
        }
        if (className.contains("argument") || className.contains("illegal")) {
            return "invalid_argument";
        }
        if (className.contains("conflict")) {
            return "conflict_error";
        }

        return "unknown_error";
    }

    private static String determineErrorCode(Throwable exception) {
        String className = exception.getClass().getSimpleName();
        String prefix = "SYNC";

        if (exception.getClass().getName().toLowerCase().contains("connection")) {
            return prefix + "_CONN_001";
        }
        if (exception.getClass().getName().toLowerCase().contains("sql")) {
            return prefix + "_DB_001";
        }
        if (exception.getClass().getName().toLowerCase().contains("timeout")) {
            return prefix + "_TIMEOUT_001";
        }

        return prefix + "_ERR_001";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RetryFailureDetail that = (RetryFailureDetail) o;
        return Objects.equals(detailId, that.detailId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(detailId);
    }

    @Override
    public String toString() {
        return "RetryFailureDetail{" +
                "detailId='" + detailId + '\'' +
                ", taskId='" + taskId + '\'' +
                ", syncId='" + syncId + '\'' +
                ", failureType='" + failureType + '\'' +
                ", retryAttempt=" + retryAttempt +
                ", resolutionStatus='" + resolutionStatus + '\'' +
                '}';
    }
}
