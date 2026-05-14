package com.healthtrack.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "health_statistics")
public class HealthStatistics {
    
    @Id
    @Column(name = "stat_id", nullable = false)
    private String statId;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;
    
    @Column(name = "total_records", nullable = false)
    private Integer totalRecords;
    
    @Column(name = "normal_count", nullable = false)
    private Integer normalCount;
    
    @Column(name = "abnormal_count", nullable = false)
    private Integer abnormalCount;
    
    @Column(name = "goal_progress")
    private Integer goalProgress;
    
    @Column(name = "avg_heart_rate")
    private Double avgHeartRate;
    
    @Column(name = "avg_weight")
    private Double avgWeight;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public HealthStatistics() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.totalRecords = 0;
        this.normalCount = 0;
        this.abnormalCount = 0;
    }

    public String getStatId() { return statId; }
    public void setStatId(String statId) { this.statId = statId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public Integer getTotalRecords() { return totalRecords; }
    public void setTotalRecords(Integer totalRecords) { this.totalRecords = totalRecords; }
    public Integer getNormalCount() { return normalCount; }
    public void setNormalCount(Integer normalCount) { this.normalCount = normalCount; }
    public Integer getAbnormalCount() { return abnormalCount; }
    public void setAbnormalCount(Integer abnormalCount) { this.abnormalCount = abnormalCount; }
    public Integer getGoalProgress() { return goalProgress; }
    public void setGoalProgress(Integer goalProgress) { this.goalProgress = goalProgress; }
    public Double getAvgHeartRate() { return avgHeartRate; }
    public void setAvgHeartRate(Double avgHeartRate) { this.avgHeartRate = avgHeartRate; }
    public Double getAvgWeight() { return avgWeight; }
    public void setAvgWeight(Double avgWeight) { this.avgWeight = avgWeight; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
