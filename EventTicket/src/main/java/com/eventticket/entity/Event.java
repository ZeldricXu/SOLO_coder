package com.eventticket.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "events")
public class Event {
    @Id
    @Column(name = "event_id", length = 50)
    private String eventId;

    @Column(name = "event_name", length = 100, nullable = false)
    private String eventName;

    @Column(name = "event_type", length = 50, nullable = false)
    private String eventType;

    @Column(name = "event_date", nullable = false)
    private LocalDateTime eventDate;

    @Column(name = "event_venue", length = 200, nullable = false)
    private String eventVenue;

    @Column(name = "event_capacity", nullable = false)
    private Integer eventCapacity;

    @Column(name = "event_status", length = 50, nullable = false)
    private String eventStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
