package com.datasync.dto;

import com.datasync.model.ConflictRecord;
import com.datasync.model.SyncRecord;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SyncStatusResponse {

    @JsonProperty("sync_records")
    private List<SyncRecord> syncRecords;

    @JsonProperty("conflicts")
    private List<ConflictRecord> conflicts;

    @JsonProperty("total_records")
    private int totalRecords;

    @JsonProperty("total_conflicts")
    private int totalConflicts;

    @JsonProperty("task_status")
    private String taskStatus;

    @JsonProperty("last_sync_time")
    private String lastSyncTime;

    @JsonProperty("next_sync_time")
    private String nextSyncTime;

    public SyncStatusResponse() {
    }

    public List<SyncRecord> getSyncRecords() {
        return syncRecords;
    }

    public void setSyncRecords(List<SyncRecord> syncRecords) {
        this.syncRecords = syncRecords;
        this.totalRecords = syncRecords != null ? syncRecords.size() : 0;
    }

    public List<ConflictRecord> getConflicts() {
        return conflicts;
    }

    public void setConflicts(List<ConflictRecord> conflicts) {
        this.conflicts = conflicts;
        this.totalConflicts = conflicts != null ? conflicts.size() : 0;
    }

    public int getTotalRecords() {
        return totalRecords;
    }

    public void setTotalRecords(int totalRecords) {
        this.totalRecords = totalRecords;
    }

    public int getTotalConflicts() {
        return totalConflicts;
    }

    public void setTotalConflicts(int totalConflicts) {
        this.totalConflicts = totalConflicts;
    }

    public String getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(String taskStatus) {
        this.taskStatus = taskStatus;
    }

    public String getLastSyncTime() {
        return lastSyncTime;
    }

    public void setLastSyncTime(String lastSyncTime) {
        this.lastSyncTime = lastSyncTime;
    }

    public String getNextSyncTime() {
        return nextSyncTime;
    }

    public void setNextSyncTime(String nextSyncTime) {
        this.nextSyncTime = nextSyncTime;
    }
}
