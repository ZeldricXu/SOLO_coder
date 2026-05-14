package com.iotconnect.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "device_type_batch_configs")
public class DeviceTypeBatchConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_type", length = 64, unique = true, nullable = false)
    private String deviceType;

    @Column(name = "batch_size", nullable = false)
    private Integer batchSize;

    @Column(name = "window_seconds", nullable = false)
    private Integer windowSeconds;

    @Column(name = "max_buffer_size")
    private Integer maxBufferSize;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public DeviceTypeBatchConfig() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public Integer getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(Integer windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public Integer getMaxBufferSize() {
        return maxBufferSize;
    }

    public void setMaxBufferSize(Integer maxBufferSize) {
        this.maxBufferSize = maxBufferSize;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
