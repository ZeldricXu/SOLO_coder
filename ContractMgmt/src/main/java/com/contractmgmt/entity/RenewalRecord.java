package com.contractmgmt.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "renewal_records")
public class RenewalRecord {

    @Id
    @Column(name = "renewal_id")
    private String renewalId;

    @Column(name = "contract_id", nullable = false)
    private String contractId;

    @Column(name = "original_contract_id", nullable = false)
    private String originalContractId;

    @Column(name = "renewal_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal renewalAmount;

    @Column(name = "renewal_start", nullable = false)
    private LocalDate renewalStart;

    @Column(name = "renewal_end", nullable = false)
    private LocalDate renewalEnd;

    @Column(name = "renewal_reason")
    private String renewalReason;

    @Column(name = "renewal_status", nullable = false)
    private String renewalStatus;

    @Column(name = "approver")
    private String approver;

    @Column(name = "approval_comment")
    private String approvalComment;

    @Column(name = "renewal_time", nullable = false)
    private LocalDateTime renewalTime;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public RenewalRecord() {
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public String getRenewalId() {
        return renewalId;
    }

    public void setRenewalId(String renewalId) {
        this.renewalId = renewalId;
    }

    public String getContractId() {
        return contractId;
    }

    public void setContractId(String contractId) {
        this.contractId = contractId;
    }

    public String getOriginalContractId() {
        return originalContractId;
    }

    public void setOriginalContractId(String originalContractId) {
        this.originalContractId = originalContractId;
    }

    public BigDecimal getRenewalAmount() {
        return renewalAmount;
    }

    public void setRenewalAmount(BigDecimal renewalAmount) {
        this.renewalAmount = renewalAmount;
    }

    public LocalDate getRenewalStart() {
        return renewalStart;
    }

    public void setRenewalStart(LocalDate renewalStart) {
        this.renewalStart = renewalStart;
    }

    public LocalDate getRenewalEnd() {
        return renewalEnd;
    }

    public void setRenewalEnd(LocalDate renewalEnd) {
        this.renewalEnd = renewalEnd;
    }

    public String getRenewalReason() {
        return renewalReason;
    }

    public void setRenewalReason(String renewalReason) {
        this.renewalReason = renewalReason;
    }

    public String getRenewalStatus() {
        return renewalStatus;
    }

    public void setRenewalStatus(String renewalStatus) {
        this.renewalStatus = renewalStatus;
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

    public LocalDateTime getRenewalTime() {
        return renewalTime;
    }

    public void setRenewalTime(LocalDateTime renewalTime) {
        this.renewalTime = renewalTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
