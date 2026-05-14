package com.stockmgmt.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "stock_warning_aggregation_config")
public class WarningAggregationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warning_level", nullable = false, length = 32)
    private String warningLevel;

    @Column(name = "warning_type", nullable = false, length = 32)
    private String warningType;

    @Column(name = "product_id", length = 64)
    private String productId;

    @Column(name = "warehouse_id", length = 64)
    private String warehouseId;

    @Column(name = "aggregation_window_seconds", nullable = false)
    private Integer aggregationWindowSeconds;

    @Column(name = "max_notifications_per_window", nullable = false)
    private Integer maxNotificationsPerWindow;

    @Column(name = "notification_cooldown_seconds", nullable = false)
    private Integer notificationCooldownSeconds;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "remark", length = 512)
    private String remark;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (enabled == null) {
            enabled = true;
        }
        if (maxNotificationsPerWindow == null) {
            maxNotificationsPerWindow = 1;
        }
        if (notificationCooldownSeconds == null) {
            notificationCooldownSeconds = 300;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
