package com.social.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "follows")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Follow {
    @Id
    @Column(name = "follow_id", nullable = false, length = 50)
    private String followId;

    @Column(name = "follower_id", nullable = false, length = 50)
    private String followerId;

    @Column(name = "following_id", nullable = false, length = 50)
    private String followingId;

    @Column(name = "follow_status", nullable = false, length = 20)
    private String followStatus = "active";

    @Column(name = "follow_time")
    private LocalDateTime followTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (followTime == null) {
            followTime = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Version
    private Long version;
}
