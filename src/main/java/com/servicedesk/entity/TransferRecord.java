package com.servicedesk.entity;

import lombok.Data;

import javax.persistence.*;
import java.time.Instant;

@Data
@Entity
@Table(name = "transfer_records")
public class TransferRecord {
    @Id
    @Column(name = "transfer_id", length = 50)
    private String transferId;

    @Column(name = "ticket_id", length = 50, nullable = false)
    private String ticketId;

    @Column(name = "from_agent", length = 50, nullable = false)
    private String fromAgent;

    @Column(name = "to_agent", length = 50, nullable = false)
    private String toAgent;

    @Column(name = "transfer_reason", columnDefinition = "TEXT", nullable = false)
    private String transferReason;

    @Column(name = "transfer_time", nullable = false)
    private Instant transferTime;

    @PrePersist
    protected void onCreate() {
        if (transferTime == null) {
            transferTime = Instant.now();
        }
    }
}
