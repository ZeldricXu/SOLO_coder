package com.datamigrate.entity;

import com.datamigrate.common.DiffType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "diff_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "diff_id", length = 64, unique = true)
    private String diffId;

    @Column(name = "verify_id", nullable = false, length = 64)
    private String verifyId;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "record_key", nullable = false, columnDefinition = "TEXT")
    private String recordKey;

    @Column(name = "source_value", columnDefinition = "TEXT")
    private String sourceValue;

    @Column(name = "target_value", columnDefinition = "TEXT")
    private String targetValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "diff_type", length = 32)
    private DiffType diffType;

    @Column(name = "diff_fields", columnDefinition = "TEXT")
    private String diffFields;

    @Column(name = "detected_at")
    private LocalDateTime detectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (detectedAt == null) {
            detectedAt = LocalDateTime.now();
        }
    }
}
