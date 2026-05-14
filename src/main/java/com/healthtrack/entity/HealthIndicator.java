package com.healthtrack.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "health_indicator")
public class HealthIndicator {
    
    @Id
    @Column(name = "indicator_id", nullable = false)
    private String indicatorId;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(name = "indicator_type", nullable = false)
    private String indicatorType;
    
    @Column(name = "current_value", nullable = false)
    private Double currentValue;
    
    @Column(name = "average_value")
    private Double averageValue;
    
    @Column(name = "target_value")
    private Double targetValue;
    
    @Column(name = "max_value")
    private Double maxValue;
    
    @Column(name = "min_value")
    private Double minValue;
    
    @Column(name = "trend")
    private String trend;
    
    @Column(name = "status", nullable = false)
    private String status;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public HealthIndicator() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public String getIndicatorId() { return indicatorId; }
    public void setIndicatorId(String indicatorId) { this.indicatorId = indicatorId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getIndicatorType() { return indicatorType; }
    public void setIndicatorType(String indicatorType) { this.indicatorType = indicatorType; }
    public Double getCurrentValue() { return currentValue; }
    public void setCurrentValue(Double currentValue) { this.currentValue = currentValue; }
    public Double getAverageValue() { return averageValue; }
    public void setAverageValue(Double averageValue) { this.averageValue = averageValue; }
    public Double getTargetValue() { return targetValue; }
    public void setTargetValue(Double targetValue) { this.targetValue = targetValue; }
    public Double getMaxValue() { return maxValue; }
    public void setMaxValue(Double maxValue) { this.maxValue = maxValue; }
    public Double getMinValue() { return minValue; }
    public void setMinValue(Double minValue) { this.minValue = minValue; }
    public String getTrend() { return trend; }
    public void setTrend(String trend) { this.trend = trend; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
