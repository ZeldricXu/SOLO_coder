package com.contractmgmt.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "execution_records")
public class ExecutionRecord {

    @Id
    @Column(name = "execution_id")
    private String executionId;

    @Column(name = "contract_id", nullable = false)
    private String contractId;

    @Column(name = "execution_type", nullable = false)
    private String executionType;

    @Column(name = "execution_amount", precision = 15, scale = 2)
    private BigDecimal executionAmount;

    @Column(name = "execution_progress", nullable = false)
    private Integer executionProgress;

    @Column(name = "execution_description")
    private String executionDescription;

    @Column(name = "execution_time", nullable = false)
    private LocalDateTime executionTime;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ExecutionRecord() {
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getContractId() {
        return contractId;
    }

    public void setContractId(String contractId) {
        this.contractId = contractId;
    }

    public String getExecutionType() {
        return executionType;
    }

    public void setExecutionType(String executionType) {
        this.executionType = executionType;
    }

    public BigDecimal getExecutionAmount() {
        return executionAmount;
    }

    public void setExecutionAmount(BigDecimal executionAmount) {
        this.executionAmount = executionAmount;
    }

    public Integer getExecutionProgress() {
        return executionProgress;
    }

    public void setExecutionProgress(Integer executionProgress) {
        this.executionProgress = executionProgress;
    }

    public String getExecutionDescription() {
        return executionDescription;
    }

    public void setExecutionDescription(String executionDescription) {
        this.executionDescription = executionDescription;
    }

    public LocalDateTime getExecutionTime() {
        return executionTime;
    }

    public void setExecutionTime(LocalDateTime executionTime) {
        this.executionTime = executionTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
