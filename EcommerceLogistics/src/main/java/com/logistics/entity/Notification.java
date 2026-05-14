package com.logistics.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @Column(name = "notify_id", nullable = false, unique = true)
    private String notifyId;

    @Column(name = "logistics_id", nullable = false)
    private String logisticsId;

    @Column(name = "notify_type", nullable = false)
    private String notifyType;

    @Column(name = "notify_status", nullable = false)
    private String notifyStatus;

    @Column(name = "notify_time", nullable = false)
    private LocalDateTime notifyTime;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (notifyTime == null) {
            notifyTime = LocalDateTime.now();
        }
        if (isRead == null) {
            isRead = false;
        }
    }
}
