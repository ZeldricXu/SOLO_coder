package com.adplatform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ad_info")
public class AdInfo {
    @Id
    @Column(name = "ad_id", length = 50)
    private String adId;

    @Column(name = "ad_name", length = 200, nullable = false)
    private String adName;

    @Column(name = "ad_type", length = 50, nullable = false)
    private String adType;

    @Column(name = "ad_content", columnDefinition = "TEXT", nullable = false)
    private String adContent;

    @Column(name = "ad_status", length = 50, nullable = false)
    private String adStatus;

    @Column(name = "advertiser", length = 100, nullable = false)
    private String advertiser;

    @Column(name = "emergency_level", length = 20)
    @Builder.Default
    private String emergencyLevel = "normal";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (emergencyLevel == null) {
            emergencyLevel = "normal";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
