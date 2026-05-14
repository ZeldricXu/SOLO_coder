package com.parking.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SettlementRecord {
    @Id
    @Column(name = "settlement_id", nullable = false, length = 50)
    private String settlementId;

    @Column(name = "entry_id", nullable = false, length = 50)
    private String entryId;

    @Column(name = "exit_id", length = 50)
    private String exitId;

    @Column(name = "vehicle_id", nullable = false, length = 50)
    private String vehicleId;

    @Column(name = "parking_fee", nullable = false)
    private double parkingFee;

    @Column(name = "payment_method", length = 20)
    private String paymentMethod;

    @Column(name = "payment_status", nullable = false, length = 20)
    private String paymentStatus = "pending";

    @Column(name = "settlement_time")
    private LocalDateTime settlementTime;

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
}
