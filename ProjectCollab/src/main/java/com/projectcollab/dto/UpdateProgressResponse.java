package com.projectcollab.dto;

public class UpdateProgressResponse {
    
    private int projectProgress;

    public UpdateProgressResponse() {
    }

    public UpdateProgressResponse(int projectProgress) {
        this.projectProgress = projectProgress;
    }

    public int getProjectProgress() {
        return projectProgress;
    }

    public void setProjectProgress(int projectProgress) {
        this.projectProgress = projectProgress;
    }
}
