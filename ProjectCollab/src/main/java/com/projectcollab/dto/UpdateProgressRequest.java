package com.projectcollab.dto;

public class UpdateProgressRequest {
    
    private String taskId;
    private int taskProgress;

    public UpdateProgressRequest() {
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public int getTaskProgress() {
        return taskProgress;
    }

    public void setTaskProgress(int taskProgress) {
        this.taskProgress = taskProgress;
    }
}
