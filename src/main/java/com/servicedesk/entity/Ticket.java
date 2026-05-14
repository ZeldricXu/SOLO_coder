package com.servicedesk.entity;

import lombok.Data;

import javax.persistence.*;
import java.time.Instant;

@Data
@Entity
@Table(name = "tickets")
public class Ticket {
    @Id
    @Column(name = "ticket_id", length = 50)
    private String ticketId;

    @Column(name = "ticket_title", length = 200, nullable = false)
    private String ticketTitle;

    @Column(name = "ticket_content", columnDefinition = "TEXT", nullable = false)
    private String ticketContent;

    @Column(name = "ticket_category", length = 50, nullable = false)
    private String ticketCategory;

    @Column(name = "ticket_priority", length = 20, nullable = false)
    private String ticketPriority;

    @Column(name = "ticket_status", length = 20, nullable = false)
    private String ticketStatus;

    @Column(name = "customer_id", length = 50, nullable = false)
    private String customerId;

    @Column(name = "assignee_id", length = 50)
    private String assigneeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "first_response_at")
    private Instant firstResponseAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "response_time_seconds")
    private Long responseTimeSeconds;

    @Column(name = "resolution_time_seconds")
    private Long resolutionTimeSeconds;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
