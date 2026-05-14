package com.fitnesscenter.dto;

public class PlanRequest {

    private String memberId;
    private String planType;
    private Integer planDuration;
    private String planTarget;

    public PlanRequest() {}

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
}
