package com.paycenter.entity;

import com.paycenter.enums.TransactionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "transaction_status_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionStatusLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TransactionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TransactionStatus toStatus;

    @Column(length = 512)
    private String remark;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
