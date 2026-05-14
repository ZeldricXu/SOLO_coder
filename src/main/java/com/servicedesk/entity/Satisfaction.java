package com.servicedesk.entity;

import lombok.Data;

import javax.persistence.*;
import java.time.Instant;

@Data
@Entity
@Table(name = "satisfactions")
public class Satisfaction {
    @Id
    @Column(name = "satisfaction_id", length = 50)
    private String satisfactionId;

    @Column(name = "ticket_id", length = 50, nullable = false, unique = true)
    private String ticketId;

    @Column(name = "customer_id", length = 50, nullable = false)
    private String customerId;

    @Column(name = "satisfaction_score", nullable = false)
    private Integer satisfactionScore;

    @Column(name = "satisfaction_comment", columnDefinition = "TEXT")
    private String satisfactionComment;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @PrePersist
    protected void onCreate() {
        if (evaluatedAt == null) {
            evaluatedAt = Instant.now();
        }
    }
}
