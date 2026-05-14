package com.stockmgmt.entity;

import com.stockmgmt.enums.LockStatus;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stock_lock", indexes = {
    @Index(name = "idx_stock_id", columnList = "stock_id"),
    @Index(name = "idx_status", columnList = "status"),
    @Index(name = "idx_reference_no", columnList = "reference_no"),
    @Index(name = "idx_urgency_level", columnList = "urgency_level"),
    @Index(name = "idx_expire_time", columnList = "expire_time")
})
public class StockLock {

    @Id
    @GeneratedValue(generator = "system-uuid")
    @GenericGenerator(name = "system-uuid", strategy = "uuid")
    @Column(name = "lock_id", length = 36)
    private String lockId;

    @Column(name = "stock_id", nullable = false, length = 36)
    private String stockId;

    @Column(name = "product_id", length = 50)
    private String productId;

    @Column(name = "locked_quantity", nullable = false)
    private Integer lockedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LockStatus status;

    @Column(name = "reference_no", length = 50)
    private String referenceNo;

    @Column(name = "operator", length = 50)
    private String operator;

    @Column(name = "expire_time")
    private LocalDateTime expireTime;

    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds;

    @Column(name = "urgency_level", length = 32)
    private String urgencyLevel;

    @CreationTimestamp
    @Column(name = "locked_at", updatable = false)
    private LocalDateTime lockedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "remark", length = 500)
    private String remark;
}
