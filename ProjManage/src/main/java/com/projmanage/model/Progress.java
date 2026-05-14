package com.projmanage.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "progresses")
public class Progress {

    @Id
    @Column(name = "progress_id", nullable = false, length = 64)
    private String progressId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "total_tasks")
    private Integer totalTasks;

    @Column(name = "completed_tasks")
    private Integer completedTasks;

    @Column(name = "in_progress_tasks")
    private Integer inProgressTasks;

    @Column(name = "pending_tasks")
    private Integer pendingTasks;

    @Column(name = "overall_progress")
    private Integer overallProgress;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Progress() {
    }

    public String getProgressId() {
        return progressId;
    }

    public void setProgressId(String progressId) {
        this.progressId = progressId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public Integer getTotalTasks() {
        return totalTasks;
    }

    public void setTotalTasks(Integer totalTasks) {
        this.totalTasks = totalTasks;
    }

    public Integer getCompletedTasks() {
        return completedTasks;
    }

    public void setCompletedTasks(Integer completedTasks) {
        this.completedTasks = completedTasks;
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

    public Integer getOverallProgress() {
        return overallProgress;
    }

    public void setOverallProgress(Integer overallProgress) {
        this.overallProgress = overallProgress;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
