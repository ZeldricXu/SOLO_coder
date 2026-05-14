package com.datasync.model;

import com.datasync.common.Constants;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SyncTaskConfig {

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("source_id")
    private String sourceId;

    @JsonProperty("target_id")
    private String targetId;

    @JsonProperty("sync_mode")
    private String syncMode;

    @JsonProperty("sync_interval")
    private Integer syncInterval;

    @JsonProperty("conflict_strategy")
    private String conflictStrategy;

    @JsonProperty("retry_count")
    private Integer retryCount;

    @JsonProperty("enabled")
    private Boolean enabled;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;

    @JsonProperty("filter_rule")
    private String filterRule;

    @JsonProperty("data_key_field")
    private String dataKeyField;

    @JsonProperty("version_field")
    private String versionField;

    public SyncTaskConfig() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.enabled = true;
        this.retryCount = Constants.DEFAULT_RETRY_COUNT;
        this.syncInterval = Constants.DEFAULT_SYNC_INTERVAL;
        this.syncMode = Constants.SYNC_MODE_SCHEDULED;
        this.conflictStrategy = Constants.CONFLICT_STRATEGY_SOURCE_PRIORITY;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getSyncMode() {
        return syncMode;
    }

    public void setSyncMode(String syncMode) {
        this.syncMode = syncMode;
    }

    public Integer getSyncInterval() {
        return syncInterval;
    }

    public void setSyncInterval(Integer syncInterval) {
        this.syncInterval = syncInterval;
    }

    public String getConflictStrategy() {
        return conflictStrategy;
    }

    public void setConflictStrategy(String conflictStrategy) {
        this.conflictStrategy = conflictStrategy;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getFilterRule() {
        return filterRule;
    }

    public void setFilterRule(String filterRule) {
        this.filterRule = filterRule;
    }

    public String getDataKeyField() {
        return dataKeyField;
    }

    public void setDataKeyField(String dataKeyField) {
        this.dataKeyField = dataKeyField;
    }

    public String getVersionField() {
        return versionField;
    }

    public void setVersionField(String versionField) {
        this.versionField = versionField;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncTaskConfig that = (SyncTaskConfig) o;
        return Objects.equals(taskId, that.taskId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId);
    }

    @Override
    public String toString() {
        return "SyncTaskConfig{" +
                "taskId='" + taskId + '\'' +
                ", sourceId='" + sourceId + '\'' +
                ", targetId='" + targetId + '\'' +
                ", syncMode='" + syncMode + '\'' +
                ", enabled=" + enabled +
                '}';
    }
}
