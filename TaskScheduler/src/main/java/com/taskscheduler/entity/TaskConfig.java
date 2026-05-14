package com.taskscheduler.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "task_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskConfig {

    @Id
    @Column(name = "task_id", length = 100, nullable = false)
    private String taskId;

    @Column(name = "task_name", length = 200, nullable = false)
    private String taskName;

    @Column(name = "task_type", length = 50, nullable = false)
    private String taskType;

    @Column(name = "execute_command", length = 2000, nullable = false)
    private String executeCommand;

    @Column(name = "cron_expression", length = 100)
    private String cronExpression;

    @Column(name = "timezone", length = 50)
    private String timezone;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "timeout_seconds", nullable = false)
    private Integer timeoutSeconds = 300;

    @Column(name = "priority", nullable = false)
    private Integer priority = 1;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "task_dependencies", joinColumns = @JoinColumn(name = "task_id"))
    @Column(name = "depends_on")
    private List<String> dependencies = new ArrayList<>();

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "max_concurrent", nullable = false)
    private Integer maxConcurrent = 1;

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
