package com.projmanage.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "project_activity")
public class ProjectActivity {

    @Id
    @Column(name = "activity_id", nullable = false, length = 64)
    private String activityId;

    @Column(name = "project_id", nullable = false, length = 64, unique = true)
    private String projectId;

    @Column(name = "update_count", nullable = false)
    private Integer updateCount;

    @Column(name = "last_activity_time")
    private LocalDateTime lastActivityTime;

    @Column(name = "activity_level", nullable = false, length = 32)
    private String activityLevel;

    @Column(name = "stat_frequency_minutes", nullable = false)
    private Integer statFrequencyMinutes;

    @Column(name = "last_stat_time")
    private LocalDateTime lastStatTime;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ProjectActivity() {
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public Integer getUpdateCount() {
        return updateCount;
    }

    public void setUpdateCount(Integer updateCount) {
        this.updateCount = updateCount;
    }

    public LocalDateTime getLastActivityTime() {
        return lastActivityTime;
    }

    public void setLastActivityTime(LocalDateTime lastActivityTime) {
        this.lastActivityTime = lastActivityTime;
    }

    public String getActivityLevel() {
        return activityLevel;
    }

    public void setActivityLevel(String activityLevel) {
        this.activityLevel = activityLevel;
    }

    public Integer getStatFrequencyMinutes() {
        return statFrequencyMinutes;
    }

    public void setStatFrequencyMinutes(Integer statFrequencyMinutes) {
        this.statFrequencyMinutes = statFrequencyMinutes;
    }

    public LocalDateTime getLastStatTime() {
        return lastStatTime;
    }

    public void setLastStatTime(LocalDateTime lastStatTime) {
        this.lastStatTime = lastStatTime;
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
