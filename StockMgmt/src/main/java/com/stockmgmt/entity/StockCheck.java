package com.stockmgmt.entity;

import com.stockmgmt.enums.CheckStatus;
import com.stockmgmt.enums.CheckType;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stock_check", indexes = {
    @Index(name = "idx_warehouse", columnList = "warehouse_id"),
    @Index(name = "idx_status", columnList = "check_status")
})
public class StockCheck {

    @Id
    @GeneratedValue(generator = "system-uuid")
    @GenericGenerator(name = "system-uuid", strategy = "uuid")
    @Column(name = "check_id", length = 36)
    private String checkId;

    @Column(name = "check_no", unique = true, length = 50)
    private String checkNo;

    @Column(name = "warehouse_id", nullable = false, length = 50)
    private String warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false, length = 30)
    private CheckType checkType;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_status", nullable = false, length = 30)
    private CheckStatus checkStatus;

    @Column(name = "check_name", length = 100)
    private String checkName;

    @Column(name = "operator", length = 50)
    private String operator;

    @Column(name = "total_items", nullable = false)
    private Integer totalItems = 0;

    @Column(name = "checked_items", nullable = false)
    private Integer checkedItems = 0;

    @Column(name = "difference_count", nullable = false)
    private Integer differenceCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "remark", length = 500)
    private String remark;
}
