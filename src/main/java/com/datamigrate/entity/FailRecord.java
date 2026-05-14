package com.datamigrate.entity;

import com.datamigrate.common.FailStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "fail_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FailRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fail_id", length = 64, unique = true)
    private String failId;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "record_key", nullable = false, columnDefinition = "TEXT")
    private String recordKey;

    @Column(name = "record_data", columnDefinition = "TEXT")
    private String recordData;

    @Column(name = "fail_reason", columnDefinition = "TEXT")
    private String failReason;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "max_retry_times")
    private Integer maxRetryTimes = 3;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32)
    private FailStatus status = FailStatus.PENDING_RETRY;

    @Column(name = "last_retry_at")
    private LocalDateTime lastRetryAt;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

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
