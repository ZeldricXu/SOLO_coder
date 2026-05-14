package com.fitnesscenter.model;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "plans")
public class Plan {

    @Id
    @Column(name = "plan_id")
    private String planId;

    @Column(name = "member_id", nullable = false)
    private String memberId;

    @Column(name = "plan_type")
    private String planType;

    @Column(name = "plan_duration")
    private Integer planDuration;

    @Column(name = "plan_target")
    private String planTarget;

    @Column(name = "plan_progress")
    private Integer planProgress = 0;

    @Column(name = "plan_status")
    private String planStatus;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "plan_content")
    private String planContent;

    public Plan() {}

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getMemberId() {
        return memberId;
    }

    public void setMemberId(String memberId) {
        this.memberId = memberId;
    }

    public String getPlanType() {
        return planType;
    }

    public void setPlanType(String planType) {
        this.planType = planType;
    }

    public Integer getPlanDuration() {
        return planDuration;
    }

    public void setPlanDuration(Integer planDuration) {
        this.planDuration = planDuration;
    }

    public String getPlanTarget() {
        return planTarget;
    }

    public void setPlanTarget(String planTarget) {
        this.planTarget = planTarget;
    }

    public Integer getPlanProgress() {
        return planProgress;
    }

    public void setPlanProgress(Integer planProgress) {
        this.planProgress = planProgress;
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

    public String getPlanContent() {
        return planContent;
    }

    public void setPlanContent(String planContent) {
        this.planContent = planContent;
    }
}
