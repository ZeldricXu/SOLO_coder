package com.crm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "opportunities")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Opportunity {
    @Id
    @Column(name = "opportunity_id", nullable = false, unique = true)
    private String opportunityId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "sales_id")
    private String salesId;

    @Column(name = "opportunity_amount")
    private Double opportunityAmount;

    @Column(name = "opportunity_stage")
    private String opportunityStage;

    @Column(name = "opportunity_status")
    private String opportunityStatus;

    @Column(name = "opportunity_prob")
    private Integer opportunityProb;

    @Column(name = "fail_reason", length = 1000)
    private String failReason;

    @Column(name = "deal_time")
    private LocalDateTime dealTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (opportunityStatus == null) {
            opportunityStatus = "following";
        }
        if (opportunityStage == null) {
            opportunityStage = "initial";
        }
        if (opportunityProb == null) {
            opportunityProb = 10;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
