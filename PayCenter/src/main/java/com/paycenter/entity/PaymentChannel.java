package com.paycenter.entity;

import com.paycenter.enums.ChannelType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_channels")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentChannel {
    @Id
    @Column(length = 64, nullable = false, unique = true)
    private String channelId;

    @Column(nullable = false)
    private String channelName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ChannelType channelType;

    @Column(columnDefinition = "TEXT")
    private String channelConfig;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal feeRate;

    @Column(nullable = false)
    private Boolean status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
