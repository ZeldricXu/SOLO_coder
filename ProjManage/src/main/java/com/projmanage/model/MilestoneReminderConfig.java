package com.projmanage.model;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "milestone_reminder_configs")
public class MilestoneReminderConfig {

    @Id
    @Column(name = "config_id", nullable = false, length = 64)
    private String configId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "milestone_id", nullable = false, length = 64)
    private String milestoneId;

    @Column(name = "reminder_days_before", nullable = false)
    private Integer reminderDaysBefore;

    @ElementCollection
    @CollectionTable(name = "reminder_days_list", joinColumns = @JoinColumn(name = "config_id"))
    @Column(name = "days_before")
    private List<Integer> reminderDaysList = new ArrayList<>();

    @Column(name = "enable_multiple_reminders", nullable = false)
    private Boolean enableMultipleReminders;

    @Column(name = "reminder_interval_hours")
    private Integer reminderIntervalHours;

    @Column(name = "max_reminder_count")
    private Integer maxReminderCount;

    @Column(name = "current_reminder_count", nullable = false)
    private Integer currentReminderCount;

    @Column(name = "last_reminder_time")
    private LocalDateTime lastReminderTime;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public MilestoneReminderConfig() {
    }

    public String getConfigId() {
        return configId;
    }

    public void setConfigId(String configId) {
        this.configId = configId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getMilestoneId() {
        return milestoneId;
    }

    public void setMilestoneId(String milestoneId) {
        this.milestoneId = milestoneId;
    }

    public Integer getReminderDaysBefore() {
        return reminderDaysBefore;
    }

    public void setReminderDaysBefore(Integer reminderDaysBefore) {
        this.reminderDaysBefore = reminderDaysBefore;
    }

    public List<Integer> getReminderDaysList() {
        return reminderDaysList;
    }

    public void setReminderDaysList(List<Integer> reminderDaysList) {
        this.reminderDaysList = reminderDaysList;
    }

    public Boolean getEnableMultipleReminders() {
        return enableMultipleReminders;
    }

    public void setEnableMultipleReminders(Boolean enableMultipleReminders) {
        this.enableMultipleReminders = enableMultipleReminders;
    }

    public Integer getReminderIntervalHours() {
        return reminderIntervalHours;
    }

    public void setReminderIntervalHours(Integer reminderIntervalHours) {
        this.reminderIntervalHours = reminderIntervalHours;
    }

    public Integer getMaxReminderCount() {
        return maxReminderCount;
    }

    public void setMaxReminderCount(Integer maxReminderCount) {
        this.maxReminderCount = maxReminderCount;
    }

    public Integer getCurrentReminderCount() {
        return currentReminderCount;
    }

    public void setCurrentReminderCount(Integer currentReminderCount) {
        this.currentReminderCount = currentReminderCount;
    }

    public LocalDateTime getLastReminderTime() {
        return lastReminderTime;
    }

    public void setLastReminderTime(LocalDateTime lastReminderTime) {
        this.lastReminderTime = lastReminderTime;
    }

    public Boolean getActive() {
        return isActive;
    }

    public void setActive(Boolean active) {
        isActive = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
