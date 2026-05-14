package com.deviceops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "status_records")
public class StatusRecord {

    @Id
    @Column(name = "status_id")
    private String statusId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "status_type", nullable = false)
    private String statusType;

    @Column(name = "status_value", nullable = false)
    private Integer statusValue;

    @Column(name = "status_time", nullable = false)
    private LocalDateTime statusTime;

    @Column(name = "status_level", nullable = false)
    private String statusLevel;

    public StatusRecord() {
    }

    @PrePersist
    protected void onCreate() {
        if (statusTime == null) {
            statusTime = LocalDateTime.now();
        }
    }

    public String getStatusId() {
        return statusId;
    }

    public void setStatusId(String statusId) {
        this.statusId = statusId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getStatusType() {
        return statusType;
    }

    public void setStatusType(String statusType) {
        this.statusType = statusType;
    }

    public Integer getStatusValue() {
        return statusValue;
    }

    public void setStatusValue(Integer statusValue) {
        this.statusValue = statusValue;
    }

    public LocalDateTime getStatusTime() {
        return statusTime;
    }

    public void setStatusTime(LocalDateTime statusTime) {
        this.statusTime = statusTime;
    }

    public String getStatusLevel() {
        return statusLevel;
    }

    public void setStatusLevel(String statusLevel) {
        this.statusLevel = statusLevel;
    }
}
