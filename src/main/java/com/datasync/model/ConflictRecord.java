package com.datasync.model;

import com.datasync.common.Constants;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConflictRecord {

    @JsonProperty("conflict_id")
    private String conflictId;

    @JsonProperty("sync_id")
    private String syncId;

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("data_key")
    private String dataKey;

    @JsonProperty("source_version")
    private String sourceVersion;

    @JsonProperty("target_version")
    private String targetVersion;

    @JsonProperty("source_value")
    private Map<String, Object> sourceValue;

    @JsonProperty("target_value")
    private Map<String, Object> targetValue;

    @JsonProperty("conflict_type")
    private String conflictType;

    @JsonProperty("resolution")
    private String resolution;

    @JsonProperty("resolved_at")
    private Instant resolvedAt;

    @JsonProperty("status")
    private String status;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("error_message")
    private String errorMessage;

    @JsonProperty("priority")
    private Integer priority;

    @JsonProperty("conflict_details")
    private Map<String, Object> conflictDetails;

    @JsonProperty("source_fields")
    private List<String> sourceFields;

    @JsonProperty("target_fields")
    private List<String> targetFields;

    @JsonProperty("added_fields")
    private List<String> addedFields;

    @JsonProperty("removed_fields")
    private List<String> removedFields;

    @JsonProperty("modified_fields")
    private List<String> modifiedFields;

    @JsonProperty("type_mismatch_fields")
    private Map<String, String> typeMismatchFields;

    public ConflictRecord() {
        this.createdAt = Instant.now();
        this.status = Constants.CONFLICT_STATUS_PENDING;
        this.priority = Constants.CONFLICT_PRIORITY_MEDIUM;
        this.conflictDetails = new HashMap<>();
        this.sourceFields = new ArrayList<>();
        this.targetFields = new ArrayList<>();
        this.addedFields = new ArrayList<>();
        this.removedFields = new ArrayList<>();
        this.modifiedFields = new ArrayList<>();
        this.typeMismatchFields = new HashMap<>();
    }

    public String getConflictId() {
        return conflictId;
    }

    public void setConflictId(String conflictId) {
        this.conflictId = conflictId;
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

    public String getDataKey() {
        return dataKey;
    }

    public void setDataKey(String dataKey) {
        this.dataKey = dataKey;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public void setSourceVersion(String sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(String targetVersion) {
        this.targetVersion = targetVersion;
    }

    public Map<String, Object> getSourceValue() {
        return sourceValue;
    }

    public void setSourceValue(Map<String, Object> sourceValue) {
        this.sourceValue = sourceValue;
    }

    public Map<String, Object> getTargetValue() {
        return targetValue;
    }

    public void setTargetValue(Map<String, Object> targetValue) {
        this.targetValue = targetValue;
    }

    public String getConflictType() {
        return conflictType;
    }

    public void setConflictType(String conflictType) {
        this.conflictType = conflictType;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Map<String, Object> getConflictDetails() {
        return conflictDetails;
    }

    public void setConflictDetails(Map<String, Object> conflictDetails) {
        this.conflictDetails = conflictDetails;
    }

    public List<String> getSourceFields() {
        return sourceFields;
    }

    public void setSourceFields(List<String> sourceFields) {
        this.sourceFields = sourceFields;
    }

    public List<String> getTargetFields() {
        return targetFields;
    }

    public void setTargetFields(List<String> targetFields) {
        this.targetFields = targetFields;
    }

    public List<String> getAddedFields() {
        return addedFields;
    }

    public void setAddedFields(List<String> addedFields) {
        this.addedFields = addedFields;
    }

    public List<String> getRemovedFields() {
        return removedFields;
    }

    public void setRemovedFields(List<String> removedFields) {
        this.removedFields = removedFields;
    }

    public List<String> getModifiedFields() {
        return modifiedFields;
    }

    public void setModifiedFields(List<String> modifiedFields) {
        this.modifiedFields = modifiedFields;
    }

    public Map<String, String> getTypeMismatchFields() {
        return typeMismatchFields;
    }

    public void setTypeMismatchFields(Map<String, String> typeMismatchFields) {
        this.typeMismatchFields = typeMismatchFields;
    }

    public void markResolved(String resolution) {
        this.resolution = resolution;
        this.resolvedAt = Instant.now();
        this.status = Constants.CONFLICT_STATUS_RESOLVED;
    }

    public void markManualRequired() {
        this.status = Constants.CONFLICT_STATUS_MANUAL_REQUIRED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConflictRecord that = (ConflictRecord) o;
        return Objects.equals(conflictId, that.conflictId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conflictId);
    }

    @Override
    public String toString() {
        return "ConflictRecord{" +
                "conflictId='" + conflictId + '\'' +
                ", syncId='" + syncId + '\'' +
                ", dataKey='" + dataKey + '\'' +
                ", conflictType='" + conflictType + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
