package com.houserental.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "applications")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaseApplication {
    @Id
    @Column(name = "application_id", nullable = false, length = 50)
    private String applicationId;

    @Column(name = "house_id", nullable = false, length = 50)
    private String houseId;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "landlord_id", nullable = false, length = 50)
    private String landlordId;

    @Column(name = "application_status", nullable = false, length = 20)
    private String applicationStatus = "pending";

    @Column(name = "reject_reason", length = 200)
    private String rejectReason;

    @Column(name = "application_time")
    private LocalDateTime applicationTime;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (applicationTime == null) {
            applicationTime = LocalDateTime.now();
        }
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
