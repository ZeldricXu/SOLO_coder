package com.houserental.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Payment {
    @Id
    @Column(name = "payment_id", nullable = false, length = 50)
    private String paymentId;

    @Column(name = "contract_id", nullable = false, length = 50)
    private String contractId;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "payment_amount", nullable = false)
    private double paymentAmount;

    @Column(name = "payment_period", nullable = false, length = 20)
    private String paymentPeriod;

    @Column(name = "payment_status", nullable = false, length = 20)
    private String paymentStatus = "pending";

    @Column(name = "payment_method", length = 20)
    private String paymentMethod = "wechat";

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Version
    private Long version;
}
