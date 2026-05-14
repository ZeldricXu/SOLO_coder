package com.stockmgmt.entity;

import com.stockmgmt.enums.DiffHandleStatus;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stock_check_diff", indexes = {
    @Index(name = "idx_check_id", columnList = "check_id"),
    @Index(name = "idx_stock_id", columnList = "stock_id"),
    @Index(name = "idx_handle_status", columnList = "handle_status")
})
public class StockCheckDiff {

    @Id
    @GeneratedValue(generator = "system-uuid")
    @GenericGenerator(name = "system-uuid", strategy = "uuid")
    @Column(name = "diff_id", length = 36)
    private String diffId;

    @Column(name = "check_id", nullable = false, length = 36)
    private String checkId;

    @Column(name = "stock_id", nullable = false, length = 36)
    private String stockId;

    @Column(name = "product_id", length = 50)
    private String productId;

    @Column(name = "product_name", length = 100)
    private String productName;

    @Column(name = "system_quantity", nullable = false)
    private Integer systemQuantity;

    @Column(name = "actual_quantity", nullable = false)
    private Integer actualQuantity;

    @Column(name = "difference", nullable = false)
    private Integer difference;

    @Column(name = "diff_reason", length = 200)
    private String diffReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "handle_status", nullable = false, length = 30)
    private DiffHandleStatus handleStatus;

    @Column(name = "approve_by", length = 50)
    private String approveBy;

    @Column(name = "approve_at")
    private LocalDateTime approveAt;

    @Column(name = "handled_by", length = 50)
    private String handledBy;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "remark", length = 500)
    private String remark;
}
