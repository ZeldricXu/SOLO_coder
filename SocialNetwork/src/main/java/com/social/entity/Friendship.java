package com.social.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "friendships")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Friendship {
    @Id
    @Column(name = "friendship_id", nullable = false, length = 50)
    private String friendshipId;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Column(name = "friend_id", nullable = false, length = 50)
    private String friendId;

    @Column(name = "friendship_status", nullable = false, length = 20)
    private String friendshipStatus = "accepted";

    @Column(name = "friendship_time")
    private LocalDateTime friendshipTime;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (friendshipTime == null) {
            friendshipTime = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Version
    private Long version;
}
