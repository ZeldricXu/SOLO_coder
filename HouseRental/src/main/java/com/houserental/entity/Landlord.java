package com.houserental.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "landlords")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Landlord {
    @Id
    @Column(name = "landlord_id", nullable = false, length = 50)
    private String landlordId;

    @Column(name = "landlord_name", nullable = false, length = 100)
    private String landlordName;

    @Column(name = "landlord_phone", nullable = false, length = 20)
    private String landlordPhone;

    @Column(name = "landlord_status", nullable = false, length = 20)
    private String landlordStatus = "active";

    @Column(name = "house_count", nullable = false)
    private int houseCount = 0;

    @Column(name = "rented_count", nullable = false)
    private int rentedCount = 0;

    @Column(name = "total_income", nullable = false)
    private double totalIncome = 0.0;

    @Column(name = "registered_at")
    private LocalDateTime registeredAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        registeredAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Version
    private Long version;
}
