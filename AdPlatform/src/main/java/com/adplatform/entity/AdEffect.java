package com.adplatform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ad_effect")
public class AdEffect {
    @Id
    @Column(name = "effect_id", length = 50)
    private String effectId;

    @Column(name = "ad_id", length = 50, nullable = false)
    private String adId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "exposure_count")
    @Builder.Default
    private Long exposureCount = 0L;

    @Column(name = "click_count")
    @Builder.Default
    private Long clickCount = 0L;

    @Column(name = "click_rate", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal clickRate = BigDecimal.ZERO;

    @Column(name = "conversion_count")
    @Builder.Default
    private Long conversionCount = 0L;

    @Column(name = "conversion_rate", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal conversionRate = BigDecimal.ZERO;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (exposureCount == null) exposureCount = 0L;
        if (clickCount == null) clickCount = 0L;
        if (conversionCount == null) conversionCount = 0L;
        if (clickRate == null) clickRate = BigDecimal.ZERO;
        if (conversionRate == null) conversionRate = BigDecimal.ZERO;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
