package com.crm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "customers")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Customer {
    @Id
    @Column(name = "customer_id", nullable = false, unique = true)
    private String customerId;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "customer_type")
    private String customerType;

    @Column(name = "customer_status")
    private String customerStatus;

    @Column(name = "customer_source")
    private String customerSource;

    @Column(name = "customer_contact")
    private String customerContact;

    @Column(name = "customer_address")
    private String customerAddress;

    @Column(name = "follow_count")
    private Integer followCount = 0;

    @Column(name = "opportunity_count")
    private Integer opportunityCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (customerStatus == null) {
            customerStatus = "potential";
        }
        if (followCount == null) {
            followCount = 0;
        }
        if (opportunityCount == null) {
            opportunityCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
