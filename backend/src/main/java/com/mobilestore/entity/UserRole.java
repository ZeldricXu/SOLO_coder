package com.mobilestore.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_roles")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRole {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    @Column(name = "user_id", length = 50, nullable = false)
    private String userId;

    @Column(name = "user_name", length = 100)
    private String userName;

    @Column(name = "role_code", length = 30, nullable = false)
    private String roleCode;

    @Column(name = "role_name", length = 50)
    private String roleName;

    @Column(name = "permissions", columnDefinition = "TEXT")
    private String permissions;

    @Column(name = "status", length = 20)
    private String status;

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
}
