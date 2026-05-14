package com.deviceops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "fault_records")
public class FaultRecord {

    @Id
    @Column(name = "fault_id")
    private String faultId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "fault_type", nullable = false)
    private String faultType;

    @Column(name = "fault_level", nullable = false)
    private String faultLevel;

    @Column(name = "fault_desc", nullable = false)
    private String faultDesc;

    @Column(name = "fault_status", nullable = false)
    private String faultStatus;

    @Column(name = "reported_at", nullable = false, updatable = false)
    private LocalDateTime reportedAt;

    @Column(name = "reported_by", nullable = false)
    private String reportedBy;

    @Column(name = "repaired_at")
    private LocalDateTime repairedAt;

    public FaultRecord() {
    }

    @PrePersist
    protected void onCreate() {
        reportedAt = LocalDateTime.now();
        if (faultStatus == null) {
            faultStatus = "pending";
        }
    }

    public String getFaultId() {
        return faultId;
    }

    public void setFaultId(String faultId) {
        this.faultId = faultId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getFaultType() {
        return faultType;
    }

    public void setFaultType(String faultType) {
        this.faultType = faultType;
    }

    public String getFaultLevel() {
        return faultLevel;
    }

    public void setFaultLevel(String faultLevel) {
        this.faultLevel = faultLevel;
    }

    public String getFaultDesc() {
        return faultDesc;
    }

    public void setFaultDesc(String faultDesc) {
        this.faultDesc = faultDesc;
    }

    public String getFaultStatus() {
        return faultStatus;
    }

    public void setFaultStatus(String faultStatus) {
        this.faultStatus = faultStatus;
    }

    public LocalDateTime getReportedAt() {
        return reportedAt;
    }

    public void setReportedAt(LocalDateTime reportedAt) {
        this.reportedAt = reportedAt;
    }

    public String getReportedBy() {
        return reportedBy;
    }

    public void setReportedBy(String reportedBy) {
        this.reportedBy = reportedBy;
    }

    public LocalDateTime getRepairedAt() {
        return repairedAt;
    }

    public void setRepairedAt(LocalDateTime repairedAt) {
        this.repairedAt = repairedAt;
    }
}
