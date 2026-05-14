package com.stockmgmt.entity;

import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stock_batch", indexes = {
    @Index(name = "idx_batch_no", columnList = "batch_no", unique = true),
    @Index(name = "idx_product_id", columnList = "product_id")
})
public class StockBatch {

    @Id
    @GeneratedValue(generator = "system-uuid")
    @GenericGenerator(name = "system-uuid", strategy = "uuid")
    @Column(name = "batch_id", length = 36)
    private String batchId;

    @Column(name = "product_id", nullable = false, length = 50)
    private String productId;

    @Column(name = "batch_quantity", nullable = false)
    private Integer batchQuantity;

    @Column(name = "remaining_quantity", nullable = false)
    private Integer remainingQuantity;

    @Column(name = "batch_no", nullable = false, unique = true, length = 50)
    private String batchNo;

    @Column(name = "production_date")
    private LocalDate productionDate;

    @Column(name = "expire_date")
    private LocalDate expireDate;

    @Column(name = "warehouse_id", length = 50)
    private String warehouseId;

    @Column(name = "supplier", length = 100)
    private String supplier;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
