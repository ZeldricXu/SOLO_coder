package com.logistics.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "delivery_tasks")
public class DeliveryTask {

    @Id
    @Column(name = "task_id", nullable = false, unique = true)
    private String taskId;

    @Column(name = "logistics_id", nullable = false)
    private String logisticsId;

    @Column(name = "courier_id", nullable = false)
    private String courierId;

    @Column(name = "station_id", nullable = false)
    private String stationId;

    @Column(name = "delivery_type_code", nullable = false)
    private String deliveryTypeCode;

    @Column(name = "urgency_level", nullable = false)
    private String urgencyLevel;

    @Column(name = "task_status", nullable = false)
    private String taskStatus;

    @Column(name = "task_time", nullable = false)
    private LocalDateTime taskTime;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (taskTime == null) {
            taskTime = LocalDateTime.now();
        }
        if (deliveryTypeCode == null) {
            deliveryTypeCode = "STANDARD";
        }
        if (urgencyLevel == null) {
            urgencyLevel = "NORMAL";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
