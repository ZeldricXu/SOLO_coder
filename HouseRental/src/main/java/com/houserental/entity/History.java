package com.houserental.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "histories")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class History {
    @Id
    @Column(name = "history_id", nullable = false, length = 50)
    private String historyId;

    @Column(name = "history_type", nullable = false, length = 30)
    private String historyType;

    @Column(name = "related_id", nullable = false, length = 50)
    private String relatedId;

    @Column(name = "related_type", nullable = false, length = 20)
    private String relatedType;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "house_id", length = 50)
    private String houseId;

    @Column(name = "tenant_id", length = 50)
    private String tenantId;

    @Column(name = "landlord_id", length = 50)
    private String landlordId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
