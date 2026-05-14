package com.assetinventory.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateTaskRequest {

    @NotBlank(message = "plan_id不能为空")
    private String planId;

    @NotBlank(message = "task_range不能为空")
    private String taskRange;

    private String taskPriority;

    public CreateTaskRequest() {
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getTaskRange() {
        return taskRange;
    }

    public void setTaskRange(String taskRange) {
        this.taskRange = taskRange;
    }

    public String getTaskPriority() {
        return taskPriority;
    }

    public void setTaskPriority(String taskPriority) {
        this.taskPriority = taskPriority;
    }
}
