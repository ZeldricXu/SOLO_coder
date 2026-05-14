package com.learningplatform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewTask implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;
    private String courseId;
    private String studentId;
    private Integer rating;
    private String content;
    private String status;
    private int retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime lastAttemptAt;
    private String errorMessage;

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_RETRY = "retry";

    public static ReviewTask create(String taskId, String courseId, String studentId,
                                    Integer rating, String content) {
        return ReviewTask.builder()
                .taskId(taskId)
                .courseId(courseId)
                .studentId(studentId)
                .rating(rating)
                .content(content)
                .status(STATUS_PENDING)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public ReviewTask incrementRetry() {
        this.retryCount++;
        this.lastAttemptAt = LocalDateTime.now();
        this.status = STATUS_RETRY;
        return this;
    }

    public ReviewTask markProcessing() {
        this.status = STATUS_PROCESSING;
        this.lastAttemptAt = LocalDateTime.now();
        return this;
    }

    public ReviewTask markCompleted() {
        this.status = STATUS_COMPLETED;
        return this;
    }

    public ReviewTask markFailed(String errorMessage) {
        this.status = STATUS_FAILED;
        this.errorMessage = errorMessage;
        return this;
    }

    public boolean canRetry(int maxRetryAttempts) {
        return this.retryCount < maxRetryAttempts;
    }
}
