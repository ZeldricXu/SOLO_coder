package com.stockmgmt.entity;

import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stock")
public class Stock {

    @Id
    @GeneratedValue(generator = "system-uuid")
    @GenericGenerator(name = "system-uuid", strategy = "uuid")
    @Column(name = "stock_id", length = 36)
    private String stockId;

    @Column(name = "product_id", nullable = false, length = 50)
    private String productId;

    @Column(name = "product_name", length = 100)
    private String productName;

    @Column(name = "sku_id", length = 50)
    private String skuId;

    @Column(name = "current_quantity", nullable = false)
    private Integer currentQuantity = 0;

    @Column(name = "available_quantity", nullable = false)
    private Integer availableQuantity = 0;

    @Column(name = "locked_quantity", nullable = false)
    private Integer lockedQuantity = 0;

    @Column(name = "warehouse_id", length = 50)
    private String warehouseId;

    @Column(name = "location_id", length = 50)
    private String locationId;

    @Column(name = "unit", length = 20)
    private String unit;

    @Column(name = "cost_price", precision = 18, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "warning_threshold", nullable = false)
    private Integer warningThreshold = 10;

    @Column(name = "overstock_threshold", nullable = false)
    private Integer overstockThreshold = 500;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version")
    private Integer version = 0;
}
