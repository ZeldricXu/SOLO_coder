package com.mobilestore.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "feedbacks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Feedback {

    @Id
    @Column(name = "feedback_id", length = 50)
    private String feedbackId;

    @Column(name = "app_id", length = 50, nullable = false)
    private String appId;

    @Column(name = "user_id", length = 50)
    private String userId;

    @Column(name = "feedback_type", length = 30)
    private String feedbackType;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "rating")
    private Integer rating;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "priority", length = 20)
    private String priority;

    @Column(name = "assignee", length = 50)
    private String assignee;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "processing_note", columnDefinition = "TEXT")
    private String processingNote;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "matched_keywords", length = 500)
    private String matchedKeywords;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
