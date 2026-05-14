package com.contractmgmt.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "change_records")
public class ChangeRecord {

    @Id
    @Column(name = "change_id")
    private String changeId;

    @Column(name = "contract_id", nullable = false)
    private String contractId;

    @Column(name = "change_type", nullable = false)
    private String changeType;

    @Column(name = "change_before", precision = 15, scale = 2)
    private BigDecimal changeBefore;

    @Column(name = "change_after", precision = 15, scale = 2)
    private BigDecimal changeAfter;

    @Column(name = "change_before_text")
    private String changeBeforeText;

    @Column(name = "change_after_text")
    private String changeAfterText;

    @Column(name = "change_reason", nullable = false)
    private String changeReason;

    @Column(name = "change_status", nullable = false)
    private String changeStatus;

    @Column(name = "approver")
    private String approver;

    @Column(name = "approval_comment")
    private String approvalComment;

    @Column(name = "change_time", nullable = false)
    private LocalDateTime changeTime;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ChangeRecord() {
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public String getChangeId() {
        return changeId;
    }

    public void setChangeId(String changeId) {
        this.changeId = changeId;
    }

    public String getContractId() {
        return contractId;
    }

    public void setContractId(String contractId) {
        this.contractId = contractId;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public BigDecimal getChangeBefore() {
        return changeBefore;
    }

    public void setChangeBefore(BigDecimal changeBefore) {
        this.changeBefore = changeBefore;
    }

    public BigDecimal getChangeAfter() {
        return changeAfter;
    }

    public void setChangeAfter(BigDecimal changeAfter) {
        this.changeAfter = changeAfter;
    }

    public String getChangeBeforeText() {
        return changeBeforeText;
    }

    public void setChangeBeforeText(String changeBeforeText) {
        this.changeBeforeText = changeBeforeText;
    }

    public String getChangeAfterText() {
        return changeAfterText;
    }

    public void setChangeAfterText(String changeAfterText) {
        this.changeAfterText = changeAfterText;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public void setChangeReason(String changeReason) {
        this.changeReason = changeReason;
    }

    public String getChangeStatus() {
        return changeStatus;
    }

    public void setChangeStatus(String changeStatus) {
        this.changeStatus = changeStatus;
    }

    public String getApprover() {
        return approver;
    }

    public void setApprover(String approver) {
        this.approver = approver;
    }

    public String getApprovalComment() {
        return approvalComment;
    }

    public void setApprovalComment(String approvalComment) {
        this.approvalComment = approvalComment;
    }

    public LocalDateTime getChangeTime() {
        return changeTime;
    }

    public void setChangeTime(LocalDateTime changeTime) {
        this.changeTime = changeTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
