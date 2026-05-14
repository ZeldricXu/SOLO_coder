package com.projectcollab.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "stages")
public class Stage {

    @Id
    @Column(name = "stage_id", length = 50)
    private String stageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "stage_name", nullable = false)
    private String stageName;

    @Column(name = "stage_order", nullable = false)
    private int stageOrder;

    @Column(name = "stage_status", nullable = false)
    private String stageStatus;

    @Column(name = "stage_progress")
    private int stageProgress;

    @Column(name = "stage_code")
    private String stageCode;

    @Column(name = "progress_warning_threshold")
    private int progressWarningThreshold;

    @Column(name = "progress_critical_threshold")
    private int progressCriticalThreshold;

    @Column(name = "progress_reminder_enabled")
    private boolean progressReminderEnabled;

    public Stage() {
    }

    public String getStageId() {
        return stageId;
    }

    public void setStageId(String stageId) {
        this.stageId = stageId;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public String getStageName() {
        return stageName;
    }

    public void setStageName(String stageName) {
        this.stageName = stageName;
    }

    public int getStageOrder() {
        return stageOrder;
    }

    public void setStageOrder(int stageOrder) {
        this.stageOrder = stageOrder;
    }

    public String getStageStatus() {
        return stageStatus;
    }

    public void setStageStatus(String stageStatus) {
        this.stageStatus = stageStatus;
    }

    public int getStageProgress() {
        return stageProgress;
    }

    public void setStageProgress(int stageProgress) {
        this.stageProgress = stageProgress;
    }

    public String getStageCode() {
        return stageCode;
    }

    public void setStageCode(String stageCode) {
        this.stageCode = stageCode;
    }

    public int getProgressWarningThreshold() {
        return progressWarningThreshold;
    }

    public void setProgressWarningThreshold(int progressWarningThreshold) {
        this.progressWarningThreshold = progressWarningThreshold;
    }

    public int getProgressCriticalThreshold() {
        return progressCriticalThreshold;
    }

    public void setProgressCriticalThreshold(int progressCriticalThreshold) {
        this.progressCriticalThreshold = progressCriticalThreshold;
    }

    public boolean isProgressReminderEnabled() {
        return progressReminderEnabled;
    }

    public void setProgressReminderEnabled(boolean progressReminderEnabled) {
        this.progressReminderEnabled = progressReminderEnabled;
    }
}
