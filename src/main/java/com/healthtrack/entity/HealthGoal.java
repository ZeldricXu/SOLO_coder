package com.healthtrack.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "health_goal")
public class HealthGoal {
    
    @Id
    @Column(name = "goal_id", nullable = false)
    private String goalId;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(name = "goal_type", nullable = false)
    private String goalType;
    
    @Column(name = "target_value", nullable = false)
    private Double targetValue;
    
    @Column(name = "current_value", nullable = false)
    private Double currentValue;
    
    @Column(name = "start_value", nullable = false)
    private Double startValue;
    
    @Column(name = "deadline")
    private LocalDate deadline;
    
    @Column(name = "progress")
    private Integer progress;
    
    @Column(name = "status", nullable = false)
    private String status;
    
    @Column(name = "description")
    private String description;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public HealthGoal() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.status = "in_progress";
        this.progress = 0;
    }

    public String getGoalId() { return goalId; }
    public void setGoalId(String goalId) { this.goalId = goalId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getGoalType() { return goalType; }
    public void setGoalType(String goalType) { this.goalType = goalType; }
    public Double getTargetValue() { return targetValue; }
    public void setTargetValue(Double targetValue) { this.targetValue = targetValue; }
    public Double getCurrentValue() { return currentValue; }
    public void setCurrentValue(Double currentValue) { this.currentValue = currentValue; }
    public Double getStartValue() { return startValue; }
    public void setStartValue(Double startValue) { this.startValue = startValue; }
    public LocalDate getDeadline() { return deadline; }
    public void setDeadline(LocalDate deadline) { this.deadline = deadline; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
