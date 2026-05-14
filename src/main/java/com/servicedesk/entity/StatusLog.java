package com.servicedesk.entity;

import lombok.Data;

import javax.persistence.*;
import java.time.Instant;

@Data
@Entity
@Table(name = "status_logs")
public class StatusLog {
    @Id
    @Column(name = "status_log_id", length = 50)
    private String statusLogId;

    @Column(name = "ticket_id", length = 50, nullable = false)
    private String ticketId;

    @Column(name = "from_status", length = 20, nullable = false)
    private String fromStatus;

    @Column(name = "to_status", length = 20, nullable = false)
    private String toStatus;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "changed_by", length = 50, nullable = false)
    private String changedBy;

    @PrePersist
    protected void onCreate() {
        if (changedAt == null) {
            changedAt = Instant.now();
        }
    }
}
