package com.finance.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "category_match_tasks")
public class CategoryMatchTask {

    @Id
    @Column(name = "task_id", nullable = false, length = 50)
    private String taskId;

    @Column(name = "record_id", nullable = false, length = 50)
    private String recordId;

    @Column(name = "account_id", nullable = false, length = 50)
    private String accountId;

    @Column(name = "record_type", nullable = false, length = 20)
    private String recordType;

    @Column(name = "requested_category", nullable = false, length = 50)
    private String requestedCategory;

    @Column(name = "matched_category", length = 50)
    private String matchedCategory;

    @Column(name = "task_status", nullable = false, length = 20)
    private String taskStatus;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "max_retries", nullable = false)
    private Integer maxRetries;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "queue_key", length = 100)
    private String queueKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;
}
