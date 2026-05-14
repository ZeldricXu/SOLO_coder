package com.social.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "posts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Post {
    @Id
    @Column(name = "post_id", nullable = false, length = 50)
    private String postId;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Column(name = "post_content", nullable = false, length = 2000)
    private String postContent;

    @Column(name = "post_type", nullable = false, length = 20)
    private String postType = "text";

    @Column(name = "post_likes", nullable = false)
    private int postLikes = 0;

    @Column(name = "post_comments", nullable = false)
    private int postComments = 0;

    @Column(name = "post_status", nullable = false, length = 20)
    private String postStatus = "published";

    @Column(name = "post_time")
    private LocalDateTime postTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (postTime == null) {
            postTime = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Version
    private Long version;
}
