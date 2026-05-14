package com.stockmgmt.entity;

import com.stockmgmt.enums.WarningLevel;
import com.stockmgmt.enums.WarningStatus;
import com.stockmgmt.enums.WarningType;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stock_warning", indexes = {
    @Index(name = "idx_stock_id", columnList = "stock_id"),
    @Index(name = "idx_warning_type", columnList = "warning_type"),
    @Index(name = "idx_status", columnList = "status")
})
public class StockWarning {

    @Id
    @GeneratedValue(generator = "system-uuid")
    @GenericGenerator(name = "system-uuid", strategy = "uuid")
    @Column(name = "warning_id", length = 36)
    private String warningId;

    @Column(name = "stock_id", nullable = false, length = 36)
    private String stockId;

    @Column(name = "product_id", length = 50)
    private String productId;

    @Column(name = "product_name", length = 100)
    private String productName;

    @Enumerated(EnumType.STRING)
    @Column(name = "warning_type", nullable = false, length = 30)
    private WarningType warningType;

    @Enumerated(EnumType.STRING)
    @Column(name = "warning_level", nullable = false, length = 20)
    private WarningLevel warningLevel;

    @Column(name = "current_quantity", nullable = false)
    private Integer currentQuantity;

    @Column(name = "threshold", nullable = false)
    private Integer threshold;

    @CreationTimestamp
    @Column(name = "triggered_at", updatable = false)
    private LocalDateTime triggeredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WarningStatus status;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    @Column(name = "handled_by", length = 50)
    private String handledBy;

    @Column(name = "remark", length = 500)
    private String remark;
}
