package com.social.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "friend_requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FriendRequest {
    @Id
    @Column(name = "request_id", nullable = false, length = 50)
    private String requestId;

    @Column(name = "from_user", nullable = false, length = 50)
    private String fromUser;

    @Column(name = "to_user", nullable = false, length = 50)
    private String toUser;

    @Column(name = "request_status", nullable = false, length = 20)
    private String requestStatus = "pending";

    @Column(name = "request_time")
    private LocalDateTime requestTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (requestTime == null) {
            requestTime = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Version
    private Long version;
}
