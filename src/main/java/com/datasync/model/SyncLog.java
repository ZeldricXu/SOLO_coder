package com.datasync.model;

import com.datasync.common.Constants;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SyncLog {

    @JsonProperty("log_id")
    private String logId;

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("sync_id")
    private String syncId;

    @JsonProperty("log_level")
    private String logLevel;

    @JsonProperty("message")
    private String message;

    @JsonProperty("timestamp")
    private Instant timestamp;

    @JsonProperty("data_key")
    private String dataKey;

    @JsonProperty("details")
    private String details;

    public SyncLog() {
        this.timestamp = Instant.now();
        this.logLevel = Constants.SYNC_LOG_LEVEL_INFO;
    }

    public String getLogId() {
        return logId;
    }

    public void setLogId(String logId) {
        this.logId = logId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getSyncId() {
        return syncId;
    }

    public void setSyncId(String syncId) {
        this.syncId = syncId;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getDataKey() {
        return dataKey;
    }

    public void setDataKey(String dataKey) {
        this.dataKey = dataKey;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public static SyncLog info(String taskId, String syncId, String message) {
        SyncLog log = new SyncLog();
        log.setTaskId(taskId);
        log.setSyncId(syncId);
        log.setLogLevel(Constants.SYNC_LOG_LEVEL_INFO);
        log.setMessage(message);
        return log;
    }

    public static SyncLog warn(String taskId, String syncId, String message) {
        SyncLog log = new SyncLog();
        log.setTaskId(taskId);
        log.setSyncId(syncId);
        log.setLogLevel(Constants.SYNC_LOG_LEVEL_WARN);
        log.setMessage(message);
        return log;
    }

    public static SyncLog error(String taskId, String syncId, String message) {
        SyncLog log = new SyncLog();
        log.setTaskId(taskId);
        log.setSyncId(syncId);
        log.setLogLevel(Constants.SYNC_LOG_LEVEL_ERROR);
        log.setMessage(message);
        return log;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncLog syncLog = (SyncLog) o;
        return Objects.equals(logId, syncLog.logId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(logId);
    }

    @Override
    public String toString() {
        return "SyncLog{" +
                "logId='" + logId + '\'' +
                ", taskId='" + taskId + '\'' +
                ", syncId='" + syncId + '\'' +
                ", logLevel='" + logLevel + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}
