package com.taskscheduler.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "fail_record", indexes = {
    @Index(name = "idx_fail_task_id", columnList = "task_id"),
    @Index(name = "idx_fail_execute_id", columnList = "execute_id"),
    @Index(name = "idx_fail_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FailRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "task_id", length = 100, nullable = false)
    private String taskId;

    @Column(name = "execute_id", length = 100, nullable = false)
    private String executeId;

    @Column(name = "fail_reason", length = 4000, nullable = false)
    private String failReason;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "status", length = 50, nullable = false)
    private String status;

    @Column(name = "next_retry_time")
    private LocalDateTime nextRetryTime;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
