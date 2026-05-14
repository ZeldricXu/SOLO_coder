package com.eventticket.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "event_schedules")
public class EventSchedule {
    @Id
    @Column(name = "schedule_id", length = 50)
    private String scheduleId;

    @Column(name = "event_id", length = 50, nullable = false)
    private String eventId;

    @Column(name = "schedule_title", length = 200, nullable = false)
    private String scheduleTitle;

    @Column(name = "schedule_start_time", nullable = false)
    private LocalDateTime scheduleStartTime;

    @Column(name = "schedule_end_time", nullable = false)
    private LocalDateTime scheduleEndTime;

    @Column(name = "schedule_venue", length = 200)
    private String scheduleVenue;

    @Column(name = "schedule_description", length = 500)
    private String scheduleDescription;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
