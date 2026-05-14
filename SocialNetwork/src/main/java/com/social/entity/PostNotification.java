package com.social.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "post_notifications")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostNotification {
    @Id
    @Column(name = "notification_id", nullable = false, length = 50)
    private String notificationId;

    @Column(name = "post_id", nullable = false, length = 50)
    private String postId;

    @Column(name = "follower_id", nullable = false, length = 50)
    private String followerId;

    @Column(name = "post_author_id", nullable = false, length = 50)
    private String postAuthorId;

    @Column(name = "notification_status", nullable = false, length = 20)
    private String notificationStatus = "pending";

    @Column(name = "delivery_status", nullable = false, length = 20)
    private String deliveryStatus = "queued";

    @Column(name = "read_status", nullable = false, length = 20)
    private String readStatus = "unread";

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (scheduledAt == null) {
            scheduledAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Version
    private Long version;
}
