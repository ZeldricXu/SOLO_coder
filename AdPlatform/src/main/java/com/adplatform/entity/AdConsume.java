package com.adplatform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ad_consume")
public class AdConsume {
    @Id
    @Column(name = "consume_id", length = 50)
    private String consumeId;

    @Column(name = "ad_id", length = 50, nullable = false)
    private String adId;

    @Column(name = "consume_type", length = 50, nullable = false)
    private String consumeType;

    @Column(name = "consume_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal consumeAmount;

    @Column(name = "consume_time", nullable = false)
    private LocalDateTime consumeTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (consumeTime == null) {
            consumeTime = LocalDateTime.now();
        }
    }
}
