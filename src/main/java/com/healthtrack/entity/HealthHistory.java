package com.healthtrack.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "health_history")
public class HealthHistory {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(name = "data_type", nullable = false)
    private String dataType;
    
    @Column(name = "action_type", nullable = false)
    private String actionType;
    
    @Column(name = "old_value")
    private Double oldValue;
    
    @Column(name = "new_value")
    private Double newValue;
    
    @Column(name = "description", length = 1000)
    private String description;
    
    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    public HealthHistory() {
        this.recordedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public Double getOldValue() { return oldValue; }
    public void setOldValue(Double oldValue) { this.oldValue = oldValue; }
    public Double getNewValue() { return newValue; }
    public void setNewValue(Double newValue) { this.newValue = newValue; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(LocalDateTime recordedAt) { this.recordedAt = recordedAt; }
}
