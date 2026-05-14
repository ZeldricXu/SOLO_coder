package com.social.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "groups")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Group {
    @Id
    @Column(name = "group_id", nullable = false, length = 50)
    private String groupId;

    @Column(name = "group_name", nullable = false, length = 100)
    private String groupName;

    @Column(name = "group_description", length = 500)
    private String groupDescription;

    @Column(name = "group_avatar", length = 500)
    private String groupAvatar;

    @Column(name = "owner_id", nullable = false, length = 50)
    private String ownerId;

    @Column(name = "group_status", nullable = false, length = 20)
    private String groupStatus = "active";

    @Column(name = "max_members", nullable = false)
    private int maxMembers = 500;

    @Column(name = "current_members", nullable = false)
    private int currentMembers = 0;

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
