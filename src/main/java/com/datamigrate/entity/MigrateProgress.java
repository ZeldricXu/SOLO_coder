package com.datamigrate.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "migrate_progress")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MigrateProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "progress_id", length = 64, unique = true)
    private String progressId;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "total_records", nullable = false)
    private Long totalRecords = 0L;

    @Column(name = "migrated_records", nullable = false)
    private Long migratedRecords = 0L;

    @Column(name = "success_records", nullable = false)
    private Long successRecords = 0L;

    @Column(name = "fail_records", nullable = false)
    private Long failRecords = 0L;

    @Column(name = "progress_rate", nullable = false)
    private Integer progressRate = 0;

    @Column(name = "current_batch")
    private Integer currentBatch = 0;

    @Column(name = "current_position")
    private Long currentPosition = 0L;

    @Column(name = "last_processed_key", columnDefinition = "TEXT")
    private String lastProcessedKey;

    @Column(name = "is_resumable")
    private Boolean isResumable = false;

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
        if (totalRecords > 0) {
            progressRate = (int) (migratedRecords * 100 / totalRecords);
        } else {
            progressRate = 0;
        }
    }
}
