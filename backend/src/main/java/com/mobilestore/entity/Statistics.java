package com.mobilestore.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "statistics")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Statistics {

    @Id
    @Column(name = "stat_id", length = 50)
    private String statId;

    @Column(name = "app_id", length = 50, nullable = false)
    private String appId;

    @Column(name = "stat_date")
    private LocalDate statDate;

    @Column(name = "download_count")
    private Long downloadCount;

    @Column(name = "active_users")
    private Long activeUsers;

    @Column(name = "avg_rating")
    private Double avgRating;

    @Column(name = "feedback_count")
    private Long feedbackCount;

    @Column(name = "created_at")
    private java.time.LocalDateTime createdAt;

    @Column(name = "updated_at")
    private java.time.LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = java.time.LocalDateTime.now();
        updatedAt = java.time.LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = java.time.LocalDateTime.now();
    }
}
