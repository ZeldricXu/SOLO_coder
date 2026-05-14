package com.contractmgmt.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "archive_records")
public class ArchiveRecord {

    @Id
    @Column(name = "archive_id")
    private String archiveId;

    @Column(name = "contract_id", nullable = false)
    private String contractId;

    @Column(name = "archive_location")
    private String archiveLocation;

    @Column(name = "archive_reason", nullable = false)
    private String archiveReason;

    @Column(name = "archive_operator", nullable = false)
    private String archiveOperator;

    @Column(name = "archive_time", nullable = false)
    private LocalDateTime archiveTime;

    @Column(name = "contract_snapshot", columnDefinition = "TEXT")
    private String contractSnapshot;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ArchiveRecord() {
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public String getArchiveId() {
        return archiveId;
    }

    public void setArchiveId(String archiveId) {
        this.archiveId = archiveId;
    }

    public String getContractId() {
        return contractId;
    }

    public void setContractId(String contractId) {
        this.contractId = contractId;
    }

    public String getArchiveLocation() {
        return archiveLocation;
    }

    public void setArchiveLocation(String archiveLocation) {
        this.archiveLocation = archiveLocation;
    }

    public String getArchiveReason() {
        return archiveReason;
    }

    public void setArchiveReason(String archiveReason) {
        this.archiveReason = archiveReason;
    }

    public String getArchiveOperator() {
        return archiveOperator;
    }

    public void setArchiveOperator(String archiveOperator) {
        this.archiveOperator = archiveOperator;
    }

    public LocalDateTime getArchiveTime() {
        return archiveTime;
    }

    public void setArchiveTime(LocalDateTime archiveTime) {
        this.archiveTime = archiveTime;
    }

    public String getContractSnapshot() {
        return contractSnapshot;
    }

    public void setContractSnapshot(String contractSnapshot) {
        this.contractSnapshot = contractSnapshot;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
