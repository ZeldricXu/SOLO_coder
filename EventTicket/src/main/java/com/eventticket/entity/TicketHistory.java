package com.eventticket.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ticket_history")
public class TicketHistory {
    @Id
    @Column(name = "history_id", length = 50)
    private String historyId;

    @Column(name = "ticket_id", length = 50, nullable = false)
    private String ticketId;

    @Column(name = "action_type", length = 50, nullable = false)
    private String actionType;

    @Column(name = "action_time", nullable = false)
    private LocalDateTime actionTime;

    @Column(name = "action_description", length = 500)
    private String actionDescription;

    @Column(name = "operator", length = 50)
    private String operator;
}
