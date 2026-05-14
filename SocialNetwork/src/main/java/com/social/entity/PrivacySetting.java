package com.social.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "privacy_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrivacySetting {
    @Id
    @Column(name = "privacy_id", nullable = false, length = 50)
    private String privacyId;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Column(name = "friend_request_policy", nullable = false, length = 20)
    private String friendRequestPolicy = "all";

    @Column(name = "message_policy", nullable = false, length = 20)
    private String messagePolicy = "all";

    @Column(name = "post_visibility", nullable = false, length = 20)
    private String postVisibility = "public";

    @Column(name = "profile_visibility", nullable = false, length = 20)
    private String profileVisibility = "public";

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
