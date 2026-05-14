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
@Table(name = "stock_warning_threshold_config", indexes = {
    @Index(name = "idx_product_warehouse", columnList = "product_id, warehouse_id"),
    @Index(name = "idx_sku_warehouse", columnList = "sku_id, warehouse_id"),
    @Index(name = "idx_config_type", columnList = "config_type")
})
public class WarningThresholdConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_type", nullable = false, length = 32)
    private String configType;

    @Column(name = "product_id", length = 64)
    private String productId;

    @Column(name = "product_name", length = 256)
    private String productName;

    @Column(name = "sku_id", length = 64)
    private String skuId;

    @Column(name = "warehouse_id", length = 64)
    private String warehouseId;

    @Column(name = "low_stock_threshold")
    private Integer lowStockThreshold;

    @Column(name = "overstock_threshold")
    private Integer overstockThreshold;

    @Column(name = "low_stock_turnover_days")
    private Integer lowStockTurnoverDays;

    @Column(name = "overstock_turnover_days")
    private Integer overstockTurnoverDays;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "remark", length = 512)
    private String remark;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

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
        if (priority == null) {
            priority = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
