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
public class DataReadTask {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_PROCESSING = "processing";

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("read_task_id")
    private String readTaskId;

    @JsonProperty("sync_id")
    private String syncId;

    @JsonProperty("data_source_id")
    private String dataSourceId;

    @JsonProperty("table_name")
    private String tableName;

    @JsonProperty("filter_condition")
    private String filterCondition;

    @JsonProperty("data_key_field")
    private String dataKeyField;

    @JsonProperty("batch_size")
    private Integer batchSize;

    @JsonProperty("offset")
    private Integer offset;

    @JsonProperty("limit")
    private Integer limit;

    @JsonProperty("priority")
    private Integer priority;

    @JsonProperty("status")
    private String status;

    @JsonProperty("total_records")
    private Integer totalRecords;

    @JsonProperty("read_records")
    private Integer readRecords;

    @JsonProperty("error_message")
    private String errorMessage;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("started_at")
    private Instant startedAt;

    @JsonProperty("completed_at")
    private Instant completedAt;

    @JsonProperty("context")
    private Map<String, Object> context;

    @JsonProperty("read_data")
    private List<Map<String, Object>> readData;

    @JsonProperty("callbacks")
    private List<String> callbacks;

    @JsonProperty("parent_read_task_id")
    private String parentReadTaskId;

    @JsonProperty("sub_task_ids")
    private List<String> subTaskIds;

    public DataReadTask() {
        this.readTaskId = "read_" + UUID.randomUUID().toString().substring(0, 12);
        this.createdAt = Instant.now();
        this.status = STATUS_PENDING;
        this.context = new HashMap<>();
        this.callbacks = new ArrayList<>();
        this.subTaskIds = new ArrayList<>();
        this.readData = new ArrayList<>();
        this.totalRecords = 0;
        this.readRecords = 0;
        this.priority = 0;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getReadTaskId() {
        return readTaskId;
    }

    public void setReadTaskId(String readTaskId) {
        this.readTaskId = readTaskId;
    }

    public String getSyncId() {
        return syncId;
    }

    public void setSyncId(String syncId) {
        this.syncId = syncId;
    }

    public String getDataSourceId() {
        return dataSourceId;
    }

    public void setDataSourceId(String dataSourceId) {
        this.dataSourceId = dataSourceId;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getFilterCondition() {
        return filterCondition;
    }

    public void setFilterCondition(String filterCondition) {
        this.filterCondition = filterCondition;
    }

    public String getDataKeyField() {
        return dataKeyField;
    }

    public void setDataKeyField(String dataKeyField) {
        this.dataKeyField = dataKeyField;
    }

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public Integer getOffset() {
        return offset;
    }

    public void setOffset(Integer offset) {
        this.offset = offset;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getTotalRecords() {
        return totalRecords;
    }

    public void setTotalRecords(Integer totalRecords) {
        this.totalRecords = totalRecords;
    }

    public Integer getReadRecords() {
        return readRecords;
    }

    public void setReadRecords(Integer readRecords) {
        this.readRecords = readRecords;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }

    public List<Map<String, Object>> getReadData() {
        return readData;
    }

    public void setReadData(List<Map<String, Object>> readData) {
        this.readData = readData;
    }

    public List<String> getCallbacks() {
        return callbacks;
    }

    public void setCallbacks(List<String> callbacks) {
        this.callbacks = callbacks;
    }

    public String getParentReadTaskId() {
        return parentReadTaskId;
    }

    public void setParentReadTaskId(String parentReadTaskId) {
        this.parentReadTaskId = parentReadTaskId;
    }

    public List<String> getSubTaskIds() {
        return subTaskIds;
    }

    public void setSubTaskIds(List<String> subTaskIds) {
        this.subTaskIds = subTaskIds;
    }

    public void markQueued() {
        this.status = STATUS_QUEUED;
    }

    public void markStarted() {
        this.status = STATUS_RUNNING;
        this.startedAt = Instant.now();
    }

    public void markCompleted(int readRecords) {
        this.status = STATUS_COMPLETED;
        this.readRecords = readRecords;
        if (this.totalRecords == null) {
            this.totalRecords = readRecords;
        }
        this.completedAt = Instant.now();
    }

    public void markFailed(String errorMessage) {
        this.status = STATUS_FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = Instant.now();
    }

    public void markCancelled() {
        this.status = STATUS_CANCELLED;
        this.completedAt = Instant.now();
    }

    public void markProcessing() {
        this.status = STATUS_PROCESSING;
    }

    public void addReadData(List<Map<String, Object>> data) {
        if (this.readData == null) {
            this.readData = new ArrayList<>();
        }
        this.readData.addAll(data);
        this.readRecords = this.readData.size();
    }

    public void addSubTask(String subTaskId) {
        if (this.subTaskIds == null) {
            this.subTaskIds = new ArrayList<>();
        }
        this.subTaskIds.add(subTaskId);
    }

    public boolean isCompleted() {
        return STATUS_COMPLETED.equals(this.status);
    }

    public boolean isFailed() {
        return STATUS_FAILED.equals(this.status);
    }

    public boolean isRunning() {
        return STATUS_RUNNING.equals(this.status) || STATUS_PROCESSING.equals(this.status);
    }

    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(this.status) ||
               STATUS_FAILED.equals(this.status) ||
               STATUS_CANCELLED.equals(this.status);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DataReadTask that = (DataReadTask) o;
        return Objects.equals(readTaskId, that.readTaskId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(readTaskId);
    }

    @Override
    public String toString() {
        return "DataReadTask{" +
                "readTaskId='" + readTaskId + '\'' +
                ", taskId='" + taskId + '\'' +
                ", syncId='" + syncId + '\'' +
                ", tableName='" + tableName + '\'' +
                ", status='" + status + '\'' +
                ", readRecords=" + readRecords +
                '}';
    }
}
