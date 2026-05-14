package com.assetmanage.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "usage_records")
public class UsageRecord {

    @Id
    @Column(name = "usage_id", length = 64)
    private String usageId;

    @Column(name = "asset_id")
    private String assetId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "usage_type")
    private String usageType;

    @Column(name = "usage_start")
    private LocalDateTime usageStart;

    @Column(name = "expected_return")
    private LocalDate expectedReturn;

    @Column(name = "actual_return")
    private LocalDateTime actualReturn;

    @Column(name = "usage_status")
    private String usageStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (this.usageStart == null) {
            this.usageStart = LocalDateTime.now();
        }
    }
}
