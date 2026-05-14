package com.taskscheduler.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "task_log", indexes = {
    @Index(name = "idx_log_execute_id", columnList = "execute_id"),
    @Index(name = "idx_log_level", columnList = "log_level"),
    @Index(name = "idx_log_time", columnList = "log_time")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "execute_id", length = 100, nullable = false)
    private String executeId;

    @Column(name = "task_id", length = 100)
    private String taskId;

    @Column(name = "log_level", length = 20, nullable = false)
    private String logLevel;

    @Column(name = "log_content", length = 4000, nullable = false)
    private String logContent;

    @Column(name = "log_time", nullable = false)
    private LocalDateTime logTime;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (logTime == null) {
            logTime = LocalDateTime.now();
        }
    }
}
