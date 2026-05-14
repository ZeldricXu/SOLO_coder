package com.healthtrack.entity;

import com.healthtrack.dto.HealthDataReportRequest;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

public class HealthDataQueueTask implements Serializable {

    private String taskId;
    private String dataId;
    private String userId;
    private String dataType;
    private Double dataValue;
    private String dataUnit;
    private String deviceId;
    private String quality;
    private LocalDateTime collectedAt;
    private int retryCount;
    private int maxRetry;
    private LocalDateTime createdAt;

    public HealthDataQueueTask() {
        this.taskId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.retryCount = 0;
        this.maxRetry = 3;
        this.createdAt = LocalDateTime.now();
    }

    public static HealthDataQueueTask fromHealthData(HealthData healthData) {
        HealthDataQueueTask task = new HealthDataQueueTask();
        task.setDataId(healthData.getDataId());
        task.setUserId(healthData.getUserId());
        task.setDataType(healthData.getDataType());
        task.setDataValue(healthData.getDataValue());
        task.setDataUnit(healthData.getDataUnit());
        task.setDeviceId(healthData.getDeviceId());
        task.setQuality(healthData.getQuality());
        task.setCollectedAt(healthData.getCollectedAt());
        return task;
    }

    public static HealthDataQueueTask fromHealthData(HealthData healthData, HealthDataReportRequest request) {
        return fromHealthData(healthData);
    }

    public HealthData toHealthData() {
        HealthData healthData = new HealthData();
        healthData.setDataId(this.dataId);
        healthData.setUserId(this.userId);
        healthData.setDataType(this.dataType);
        healthData.setDataValue(this.dataValue);
        healthData.setDataUnit(this.dataUnit);
        healthData.setDeviceId(this.deviceId);
        healthData.setCollectedAt(this.collectedAt);
        healthData.setQuality(this.quality);
        return healthData;
    }

    public boolean canRetry() {
        return this.retryCount < this.maxRetry;
    }

    public void incrementRetry() {
        this.retryCount++;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getDataId() { return dataId; }
    public void setDataId(String dataId) { this.dataId = dataId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public Double getDataValue() { return dataValue; }
    public void setDataValue(Double dataValue) { this.dataValue = dataValue; }
    public String getDataUnit() { return dataUnit; }
    public void setDataUnit(String dataUnit) { this.dataUnit = dataUnit; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }
    public LocalDateTime getCollectedAt() { return collectedAt; }
    public void setCollectedAt(LocalDateTime collectedAt) { this.collectedAt = collectedAt; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public int getMaxRetry() { return maxRetry; }
    public void setMaxRetry(int maxRetry) { this.maxRetry = maxRetry; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
