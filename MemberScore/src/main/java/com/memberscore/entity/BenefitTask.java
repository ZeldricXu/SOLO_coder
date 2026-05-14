package com.memberscore.entity;

import com.memberscore.enums.BenefitTaskStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Entity
@Table(name = "benefit_tasks")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BenefitTask {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "task_id", unique = true, nullable = false)
    private String taskId;
    
    @Column(name = "member_id", nullable = false)
    private String memberId;
    
    @Column(name = "level_id", nullable = false)
    private String levelId;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BenefitTaskStatus status;
    
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;
    
    @Column(name = "max_retries", nullable = false)
    private Integer maxRetries;
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    @Column(name = "task_data", columnDefinition = "TEXT")
    private String taskData;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "processed_at")
    private LocalDateTime processedAt;
    
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = BenefitTaskStatus.PENDING;
        }
        if (retryCount == null) {
            retryCount = 0;
        }
        if (maxRetries == null) {
            maxRetries = 3;
        }
    }
}
