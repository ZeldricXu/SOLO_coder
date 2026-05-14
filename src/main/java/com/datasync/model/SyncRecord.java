package com.datasync.model;

import com.datasync.common.Constants;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SyncRecord {

    @JsonProperty("sync_id")
    private String syncId;

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("source_records")
    private int sourceRecords;

    @JsonProperty("synced_records")
    private int syncedRecords;

    @JsonProperty("conflict_count")
    private int conflictCount;

    @JsonProperty("status")
    private String status;

    @JsonProperty("start_time")
    private Instant startTime;

    @JsonProperty("end_time")
    private Instant endTime;

    @JsonProperty("errors")
    private List<String> errors;

    @JsonProperty("retry_count")
    private int retryCount;

    @JsonProperty("sync_mode")
    private String syncMode;

    public SyncRecord() {
        this.startTime = Instant.now();
        this.status = Constants.SYNC_STATUS_PENDING;
        this.errors = new ArrayList<>();
        this.sourceRecords = 0;
        this.syncedRecords = 0;
        this.conflictCount = 0;
        this.retryCount = 0;
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

    public int getSourceRecords() {
        return sourceRecords;
    }

    public void setSourceRecords(int sourceRecords) {
        this.sourceRecords = sourceRecords;
    }

    public int getSyncedRecords() {
        return syncedRecords;
    }

    public void setSyncedRecords(int syncedRecords) {
        this.syncedRecords = syncedRecords;
    }

    public int getConflictCount() {
        return conflictCount;
    }

    public void setConflictCount(int conflictCount) {
        this.conflictCount = conflictCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors;
    }

    public void addError(String error) {
        if (this.errors == null) {
            this.errors = new ArrayList<>();
        }
        this.errors.add(error);
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getSyncMode() {
        return syncMode;
    }

    public void setSyncMode(String syncMode) {
        this.syncMode = syncMode;
    }

    public void start() {
        this.startTime = Instant.now();
        this.status = Constants.SYNC_STATUS_RUNNING;
    }

    public void complete() {
        this.endTime = Instant.now();
        this.status = Constants.SYNC_STATUS_COMPLETED;
    }

    public void fail(String error) {
        this.endTime = Instant.now();
        this.status = Constants.SYNC_STATUS_FAILED;
        addError(error);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncRecord that = (SyncRecord) o;
        return Objects.equals(syncId, that.syncId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(syncId);
    }

    @Override
    public String toString() {
        return "SyncRecord{" +
                "syncId='" + syncId + '\'' +
                ", taskId='" + taskId + '\'' +
                ", status='" + status + '\'' +
                ", syncedRecords=" + syncedRecords +
                '}';
    }
}
