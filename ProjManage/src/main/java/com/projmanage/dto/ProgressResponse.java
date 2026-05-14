package com.projmanage.dto;

public class ProgressResponse {

    private Integer overallProgress;
    private Integer completedTasks;
    private Integer totalTasks;
    private Integer inProgressTasks;
    private Integer pendingTasks;

    public ProgressResponse() {
    }

    public ProgressResponse(Integer overallProgress, Integer completedTasks, Integer totalTasks) {
        this.overallProgress = overallProgress;
        this.completedTasks = completedTasks;
        this.totalTasks = totalTasks;
    }

    public Integer getOverallProgress() {
        return overallProgress;
    }

    public void setOverallProgress(Integer overallProgress) {
        this.overallProgress = overallProgress;
    }

    public Integer getCompletedTasks() {
        return completedTasks;
    }

    public void setCompletedTasks(Integer completedTasks) {
        this.completedTasks = completedTasks;
    }

    public Integer getTotalTasks() {
        return totalTasks;
    }

    public void setTotalTasks(Integer totalTasks) {
        this.totalTasks = totalTasks;
    }

    public Integer getInProgressTasks() {
        return inProgressTasks;
    }

    public void setInProgressTasks(Integer inProgressTasks) {
        this.inProgressTasks = inProgressTasks;
    }

    public Integer getPendingTasks() {
        return pendingTasks;
    }

    public void setPendingTasks(Integer pendingTasks) {
        this.pendingTasks = pendingTasks;
    }
}
