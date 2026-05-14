package com.crm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "follows")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Follow {
    @Id
    @Column(name = "follow_id", nullable = false, unique = true)
    private String followId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "sales_id")
    private String salesId;

    @Column(name = "follow_type")
    private String followType;

    @Column(name = "follow_content", length = 2000)
    private String followContent;

    @Column(name = "follow_result")
    private String followResult;

    @Column(name = "follow_time")
    private LocalDateTime followTime;

    @Column(name = "next_follow")
    private LocalDateTime nextFollow;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (followTime == null) {
            followTime = LocalDateTime.now();
        }
    }
}
