package com.assetinventory.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "inventory_plans")
public class InventoryPlan {

    @Id
    @Column(name = "plan_id", nullable = false, length = 50)
    private String planId;

    @Column(name = "plan_name", nullable = false, length = 200)
    private String planName;

    @Column(name = "plan_range", nullable = false, length = 200)
    private String planRange;

    @Column(name = "plan_start", nullable = false)
    private LocalDate planStart;

    @Column(name = "plan_end", nullable = false)
    private LocalDate planEnd;

    @Column(name = "plan_status", nullable = false, length = 50)
    private String planStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public InventoryPlan() {
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getPlanName() {
        return planName;
    }

    public void setPlanName(String planName) {
        this.planName = planName;
    }

    public String getPlanRange() {
        return planRange;
    }

    public void setPlanRange(String planRange) {
        this.planRange = planRange;
    }

    public LocalDate getPlanStart() {
        return planStart;
    }

    public void setPlanStart(LocalDate planStart) {
        this.planStart = planStart;
    }

    public LocalDate getPlanEnd() {
        return planEnd;
    }

    public void setPlanEnd(LocalDate planEnd) {
        this.planEnd = planEnd;
    }

    public String getPlanStatus() {
        return planStatus;
    }

    public void setPlanStatus(String planStatus) {
        this.planStatus = planStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
