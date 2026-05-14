package com.houserental.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "tenants")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {
    @Id
    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "tenant_name", nullable = false, length = 100)
    private String tenantName;

    @Column(name = "tenant_phone", nullable = false, length = 20)
    private String tenantPhone;

    @Column(name = "tenant_id_type", length = 20)
    private String tenantIdType = "identity";

    @Column(name = "tenant_id_number", length = 50)
    private String tenantIdNumber;

    @Column(name = "tenant_status", nullable = false, length = 20)
    private String tenantStatus = "active";

    @Column(name = "application_count", nullable = false)
    private int applicationCount = 0;

    @Column(name = "rented_count", nullable = false)
    private int rentedCount = 0;

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
