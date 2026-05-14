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
@Table(name = "stock_lock_timeout_config")
public class LockTimeoutConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;

    @Column(name = "product_name", length = 256)
    private String productName;

    @Column(name = "sku_id", length = 64)
    private String skuId;

    @Column(name = "warehouse_id", length = 64)
    private String warehouseId;

    @Column(name = "urgency_level", nullable = false, length = 32)
    private String urgencyLevel;

    @Column(name = "timeout_seconds", nullable = false)
    private Integer timeoutSeconds;

    @Column(name = "max_retry_times", nullable = false)
    private Integer maxRetryTimes;

    @Column(name = "retry_delay_seconds", nullable = false)
    private Integer retryDelaySeconds;

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
        if (maxRetryTimes == null) {
            maxRetryTimes = 3;
        }
        if (retryDelaySeconds == null) {
            retryDelaySeconds = 1;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
