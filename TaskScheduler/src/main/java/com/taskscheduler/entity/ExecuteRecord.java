package com.taskscheduler.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "execute_record", indexes = {
    @Index(name = "idx_task_id", columnList = "task_id"),
    @Index(name = "idx_execute_status", columnList = "execute_status"),
    @Index(name = "idx_execute_time", columnList = "execute_time")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteRecord {

    @Id
    @Column(name = "execute_id", length = 100, nullable = false)
    private String executeId;

    @Column(name = "task_id", length = 100, nullable = false)
    private String taskId;

    @Column(name = "execute_time", nullable = false)
    private LocalDateTime executeTime;

    @Column(name = "executor_id", length = 100)
    private String executorId;

    @Column(name = "execute_status", length = 50, nullable = false)
    private String executeStatus;

    @Column(name = "execute_duration_seconds")
    private Long executeDurationSeconds;

    @Column(name = "execute_result", length = 4000)
    private String executeResult;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "trigger_type", length = 50)
    private String triggerType;

    @Column(name = "retry_number", nullable = false)
    private Integer retryNumber = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
