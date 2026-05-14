package com.healthtrack.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "health_data")
public class HealthData {
    
    @Id
    @Column(name = "data_id", nullable = false)
    private String dataId;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(name = "data_type", nullable = false)
    private String dataType;
    
    @Column(name = "data_value", nullable = false)
    private Double dataValue;
    
    @Column(name = "data_unit")
    private String dataUnit;
    
    @Column(name = "device_id")
    private String deviceId;
    
    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;
    
    @Column(name = "quality", nullable = false)
    private String quality;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public HealthData() {
        this.createdAt = LocalDateTime.now();
    }

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
    public LocalDateTime getCollectedAt() { return collectedAt; }
    public void setCollectedAt(LocalDateTime collectedAt) { this.collectedAt = collectedAt; }
    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
