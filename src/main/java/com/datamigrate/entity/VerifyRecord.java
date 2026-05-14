package com.datamigrate.entity;

import com.datamigrate.common.VerifyStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "verify_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerifyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "verify_id", length = 64, unique = true)
    private String verifyId;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "verify_type", length = 32)
    private String verifyType = "full";

    @Enumerated(EnumType.STRING)
    @Column(name = "verify_status", length = 32)
    private VerifyStatus verifyStatus;

    @Column(name = "total_verified", nullable = false)
    private Long totalVerified = 0L;

    @Column(name = "match_count", nullable = false)
    private Long matchCount = 0L;

    @Column(name = "diff_count", nullable = false)
    private Long diffCount = 0L;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

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
