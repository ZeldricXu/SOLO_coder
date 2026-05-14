package com.fitnesscenter.dto;

public class PlanResponse {

    private PlanInfo plan;

    public PlanResponse() {}

    public PlanResponse(PlanInfo plan) {
        this.plan = plan;
    }

    public PlanInfo getPlan() {
        return plan;
    }

    public void setPlan(PlanInfo plan) {
        this.plan = plan;
    }

    public static class PlanInfo {
        private Integer progress;
        private String status;
        private String planId;
        private String planType;
        private String planTarget;

        public PlanInfo() {}

        public PlanInfo(Integer progress, String status, String planId, String planType, String planTarget) {
            this.progress = progress;
            this.status = status;
            this.planId = planId;
            this.planType = planType;
            this.planTarget = planTarget;
        }

        public Integer getProgress() {
            return progress;
        }

        public void setProgress(Integer progress) {
            this.progress = progress;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getPlanId() {
            return planId;
        }

        public void setPlanId(String planId) {
            this.planId = planId;
        }

        public String getPlanType() {
            return planType;
        }

        public void setPlanType(String planType) {
            this.planType = planType;
        }

        public String getPlanTarget() {
            return planTarget;
        }

        public void setPlanTarget(String planTarget) {
            this.planTarget = planTarget;
        }
    }
}
