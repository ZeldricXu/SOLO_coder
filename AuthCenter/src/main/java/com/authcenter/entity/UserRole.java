package com.authcenter.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_roles")
public class UserRole {
    
    @Id
    @Column(name = "role_id", nullable = false, unique = true)
    private String roleId;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(nullable = false)
    private String role;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    public UserRole() {
    }
    
    public String getRoleId() {
        return roleId;
    }
    
    public void setRoleId(String roleId) {
        this.roleId = roleId;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getRole() {
        return role;
    }
    
    public void setRole(String role) {
        this.role = role;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}