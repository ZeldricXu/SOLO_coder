package com.contractmgmt.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "approval_records")
public class ApprovalRecord {

    @Id
    @Column(name = "approval_id")
    private String approvalId;

    @Column(name = "contract_id", nullable = false)
    private String contractId;

    @Column(name = "approval_type", nullable = false)
    private String approvalType;

    @Column(name = "approval_status", nullable = false)
    private String approvalStatus;

    @Column(name = "approver", nullable = false)
    private String approver;

    @Column(name = "approval_comment")
    private String approvalComment;

    @Column(name = "approval_time", nullable = false)
    private LocalDateTime approvalTime;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ApprovalRecord() {
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public String getApprovalId() {
        return approvalId;
    }

    public void setApprovalId(String approvalId) {
        this.approvalId = approvalId;
    }

    public String getContractId() {
        return contractId;
    }

    public void setContractId(String contractId) {
        this.contractId = contractId;
    }

    public String getApprovalType() {
        return approvalType;
    }

    public void setApprovalType(String approvalType) {
        this.approvalType = approvalType;
    }

    public String getApprovalStatus() {
        return approvalStatus;
    }

    public void setApprovalStatus(String approvalStatus) {
        this.approvalStatus = approvalStatus;
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

    public LocalDateTime getApprovalTime() {
        return approvalTime;
    }

    public void setApprovalTime(LocalDateTime approvalTime) {
        this.approvalTime = approvalTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
