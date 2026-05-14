package com.datamigrate.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "migrate_stats")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MigrateStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stat_id", length = 64, unique = true)
    private String statId;

    @Column(name = "task_id", nullable = false, length = 64, unique = true)
    private String taskId;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "total_duration_seconds")
    private Long totalDurationSeconds = 0L;

    @Column(name = "total_records")
    private Long totalRecords = 0L;

    @Column(name = "success_records")
    private Long successRecords = 0L;

    @Column(name = "fail_records")
    private Long failRecords = 0L;

    @Column(name = "avg_speed_per_second")
    private Double avgSpeedPerSecond = 0.0;

    @Column(name = "max_speed_per_second")
    private Double maxSpeedPerSecond = 0.0;

    @Column(name = "min_speed_per_second")
    private Double minSpeedPerSecond = 0.0;

    @Column(name = "batch_count")
    private Integer batchCount = 0;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "verify_match_rate")
    private Double verifyMatchRate = 0.0;

    @Column(name = "created_at", nullable = false, updatable = false)
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
