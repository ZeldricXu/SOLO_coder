package com.eventticket.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "tickets")
public class Ticket {
    @Id
    @Column(name = "ticket_id", length = 50)
    private String ticketId;

    @Column(name = "event_id", length = 50, nullable = false)
    private String eventId;

    @Column(name = "seat_id", length = 50, nullable = false)
    private String seatId;

    @Column(name = "participant_id", length = 50)
    private String participantId;

    @Column(name = "participant_name", length = 100, nullable = false)
    private String participantName;

    @Column(name = "participant_phone", length = 20, nullable = false)
    private String participantPhone;

    @Column(name = "ticket_status", length = 50, nullable = false)
    private String ticketStatus;

    @Column(name = "ticket_price", nullable = false)
    private Integer ticketPrice;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "ticket_type", length = 50)
    private String ticketType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (ticketType == null) {
            ticketType = "regular";
        }
    }
}
