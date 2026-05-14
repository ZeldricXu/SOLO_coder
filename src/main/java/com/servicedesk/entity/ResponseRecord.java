package com.servicedesk.entity;

import lombok.Data;

import javax.persistence.*;
import java.time.Instant;

@Data
@Entity
@Table(name = "response_records")
public class ResponseRecord {
    @Id
    @Column(name = "response_id", length = 50)
    private String responseId;

    @Column(name = "ticket_id", length = 50, nullable = false)
    private String ticketId;

    @Column(name = "agent_id", length = 50, nullable = false)
    private String agentId;

    @Column(name = "response_content", columnDefinition = "TEXT", nullable = false)
    private String responseContent;

    @Column(name = "response_time", nullable = false)
    private Instant responseTime;

    @Column(name = "response_type", length = 20, nullable = false)
    private String responseType;

    @PrePersist
    protected void onCreate() {
        if (responseTime == null) {
            responseTime = Instant.now();
        }
    }
}
