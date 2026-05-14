package com.paycenter.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {
    @Id
    @Column(length = 64, nullable = false, unique = true)
    private String accountId;

    @Column(nullable = false, length = 64, unique = true)
    private String merchantId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal frozenAmount;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal availableBalance;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (balance == null) {
            balance = BigDecimal.ZERO;
        }
        if (frozenAmount == null) {
            frozenAmount = BigDecimal.ZERO;
        }
        if (availableBalance == null) {
            availableBalance = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
