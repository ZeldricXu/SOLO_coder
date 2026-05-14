package com.houserental.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "statistics")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Statistics {
    @Id
    @Column(name = "stat_id", nullable = false, length = 50)
    private String statId;

    @Column(name = "stat_month", nullable = false, length = 10, unique = true)
    private String statMonth;

    @Column(name = "house_count", nullable = false)
    private int houseCount = 0;

    @Column(name = "available_house_count", nullable = false)
    private int availableHouseCount = 0;

    @Column(name = "rented_house_count", nullable = false)
    private int rentedHouseCount = 0;

    @Column(name = "application_count", nullable = false)
    private int applicationCount = 0;

    @Column(name = "approved_application_count", nullable = false)
    private int approvedApplicationCount = 0;

    @Column(name = "rejected_application_count", nullable = false)
    private int rejectedApplicationCount = 0;

    @Column(name = "contract_count", nullable = false)
    private int contractCount = 0;

    @Column(name = "renewal_count", nullable = false)
    private int renewalCount = 0;

    @Column(name = "rent_amount", nullable = false)
    private double rentAmount = 0.0;

    @Column(name = "landlord_count", nullable = false)
    private int landlordCount = 0;

    @Column(name = "tenant_count", nullable = false)
    private int tenantCount = 0;

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
