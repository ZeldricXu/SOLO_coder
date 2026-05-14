package com.stockmgmt.entity;

import com.stockmgmt.enums.OperationType;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stock_history", indexes = {
    @Index(name = "idx_stock_id", columnList = "stock_id"),
    @Index(name = "idx_operation_type", columnList = "operation_type"),
    @Index(name = "idx_operation_time", columnList = "operation_time")
})
public class StockHistory {

    @Id
    @GeneratedValue(generator = "system-uuid")
    @GenericGenerator(name = "system-uuid", strategy = "uuid")
    @Column(name = "history_id", length = 36)
    private String historyId;

    @Column(name = "stock_id", nullable = false, length = 36)
    private String stockId;

    @Column(name = "product_id", length = 50)
    private String productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 30)
    private OperationType operationType;

    @Column(name = "quantity_change", nullable = false)
    private Integer quantityChange;

    @Column(name = "before_quantity", nullable = false)
    private Integer beforeQuantity;

    @Column(name = "after_quantity", nullable = false)
    private Integer afterQuantity;

    @Column(name = "operator", length = 50)
    private String operator;

    @CreationTimestamp
    @Column(name = "operation_time", updatable = false)
    private LocalDateTime operationTime;

    @Column(name = "reference_no", length = 50)
    private String referenceNo;

    @Column(name = "remark", length = 500)
    private String remark;
}
