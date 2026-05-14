package com.social.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "social_stats")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SocialStat {
    @Id
    @Column(name = "stat_id", nullable = false, length = 50)
    private String statId;

    @Column(name = "stat_month", nullable = false, length = 20)
    private String statMonth;

    @Column(name = "user_count", nullable = false)
    private long userCount = 0;

    @Column(name = "friendship_count", nullable = false)
    private long friendshipCount = 0;

    @Column(name = "message_count", nullable = false)
    private long messageCount = 0;

    @Column(name = "post_count", nullable = false)
    private long postCount = 0;

    @Column(name = "interaction_count", nullable = false)
    private long interactionCount = 0;

    @Column(name = "created_at")
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

    @Version
    private Long version;
}
