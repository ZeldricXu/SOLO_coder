package com.eventticket.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "seats")
public class Seat {
    @Id
    @Column(name = "seat_id", length = 50)
    private String seatId;

    @Column(name = "event_id", length = 50, nullable = false)
    private String eventId;

    @Column(name = "seat_number", length = 50, nullable = false)
    private String seatNumber;

    @Column(name = "seat_section", length = 50, nullable = false)
    private String seatSection;

    @Column(name = "seat_price", nullable = false)
    private Integer seatPrice;

    @Column(name = "seat_status", length = 50, nullable = false)
    private String seatStatus;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "sold_at")
    private LocalDateTime soldAt;

    @Column(name = "admitted_at")
    private LocalDateTime admittedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
