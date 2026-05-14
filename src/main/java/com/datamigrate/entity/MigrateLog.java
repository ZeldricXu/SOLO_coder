package com.datamigrate.entity;

import com.datamigrate.common.LogLevel;
import com.datamigrate.common.LogType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "migrate_logs", indexes = {
    @Index(name = "idx_task_id", columnList = "task_id"),
    @Index(name = "idx_log_time", columnList = "log_time")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MigrateLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "log_id", length = 64, unique = true)
    private String logId;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "log_type", length = 32)
    private LogType logType;

    @Column(name = "log_content", columnDefinition = "TEXT")
    private String logContent;

    @Column(name = "log_time")
    private LocalDateTime logTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "log_level", length = 16)
    private LogLevel logLevel;

    @Column(name = "extra_info", columnDefinition = "TEXT")
    private String extraInfo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (logTime == null) {
            logTime = LocalDateTime.now();
        }
    }
}
