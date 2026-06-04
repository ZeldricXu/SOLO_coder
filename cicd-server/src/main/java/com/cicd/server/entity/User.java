package com.cicd.server.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "users", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"username"}),
    @UniqueConstraint(columnNames = {"email"})
})
public class User extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false, length = 200)
    private String email;

    @Column(name = "password_hash", length = 200)
    private String passwordHash;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(length = 20)
    private String phone;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "is_locked", nullable = false)
    private Boolean isLocked = false;

    @Column(name = "last_login_at")
    private java.time.LocalDateTime lastLoginAt;

    @Column(name = "login_count", nullable = false)
    private Integer loginCount = 0;

    @Column(name = "provider", length = 50)
    private String provider;

    @Column(name = "provider_id", length = 200)
    private String providerId;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<UserRole> roles;
}
