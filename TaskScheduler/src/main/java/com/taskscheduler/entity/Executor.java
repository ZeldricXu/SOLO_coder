package com.taskscheduler.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "executor", indexes = {
    @Index(name = "idx_executor_status", columnList = "executor_status"),
    @Index(name = "idx_current_load", columnList = "current_load")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Executor {

    @Id
    @Column(name = "executor_id", length = 100, nullable = false)
    private String executorId;

    @Column(name = "executor_name", length = 200, nullable = false)
    private String executorName;

    @Column(name = "executor_address", length = 200, nullable = false)
    private String executorAddress;

    @Column(name = "executor_status", length = 50, nullable = false)
    private String executorStatus;

    @Column(name = "current_load", nullable = false)
    private Integer currentLoad = 0;

    @Column(name = "max_capacity", nullable = false)
    private Integer maxCapacity = 10;

    @Column(name = "task_type", length = 100)
    private String taskType;

    @Column(name = "registered_at", nullable = false)
    private LocalDateTime registeredAt;

    @Column(name = "last_active", nullable = false)
    private LocalDateTime lastActive;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        registeredAt = LocalDateTime.now();
        lastActive = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
