package com.projectcollab.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "progress_records")
public class Progress {

    @Id
    @Column(name = "progress_id", length = 50)
    private String progressId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "progress_value")
    private int progressValue;

    @Column(name = "progress_tasks_completed")
    private int progressTasksCompleted;

    @Column(name = "progress_tasks_total")
    private int progressTasksTotal;

    @Column(name = "progress_time", nullable = false)
    private LocalDateTime progressTime;

    @Column(name = "task_id", length = 50)
    private String taskId;

    public Progress() {
    }

    public String getProgressId() {
        return progressId;
    }

    public void setProgressId(String progressId) {
        this.progressId = progressId;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public int getProgressValue() {
        return progressValue;
    }

    public void setProgressValue(int progressValue) {
        this.progressValue = progressValue;
    }

    public int getProgressTasksCompleted() {
        return progressTasksCompleted;
    }

    public void setProgressTasksCompleted(int progressTasksCompleted) {
        this.progressTasksCompleted = progressTasksCompleted;
    }

    public int getProgressTasksTotal() {
        return progressTasksTotal;
    }

    public void setProgressTasksTotal(int progressTasksTotal) {
        this.progressTasksTotal = progressTasksTotal;
    }

    public LocalDateTime getProgressTime() {
        return progressTime;
    }

    public void setProgressTime(LocalDateTime progressTime) {
        this.progressTime = progressTime;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }
}
