package com.houserental.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "contracts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Contract {
    @Id
    @Column(name = "contract_id", nullable = false, length = 50)
    private String contractId;

    @Column(name = "house_id", nullable = false, length = 50)
    private String houseId;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "landlord_id", nullable = false, length = 50)
    private String landlordId;

    @Column(name = "contract_start", nullable = false)
    private LocalDate contractStart;

    @Column(name = "contract_end", nullable = false)
    private LocalDate contractEnd;

    @Column(name = "contract_rent", nullable = false)
    private double contractRent;

    @Column(name = "contract_status", nullable = false, length = 20)
    private String contractStatus = "active";

    @Column(name = "renewal_count", nullable = false)
    private int renewalCount = 0;

    @Column(name = "previous_contract_id", length = 50)
    private String previousContractId;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (signedAt == null) {
            signedAt = LocalDateTime.now();
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
