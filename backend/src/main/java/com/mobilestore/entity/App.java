package com.mobilestore.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "apps")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class App {

    @Id
    @Column(name = "app_id", length = 50)
    private String appId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "icon", length = 500)
    private String icon;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "platform", length = 50)
    private String platform;

    @Column(name = "developer_id", length = 50)
    private String developerId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "status", length = 20)
    private String status;

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
