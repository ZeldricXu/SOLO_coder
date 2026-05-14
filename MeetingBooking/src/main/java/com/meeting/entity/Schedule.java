package com.meeting.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "schedules")
public class Schedule {
    @Id
    @Column(name = "schedule_id", nullable = false, unique = true)
    private String scheduleId;

    @Column(name = "meeting_id", nullable = false)
    private String meetingId;

    @Column(name = "room_id", nullable = false)
    private String roomId;

    @Column(name = "schedule_date", nullable = false)
    private LocalDate scheduleDate;

    @Column(name = "schedule_start", nullable = false)
    private LocalTime scheduleStart;

    @Column(name = "schedule_end", nullable = false)
    private LocalTime scheduleEnd;

    @Column(name = "schedule_status", nullable = false)
    private String scheduleStatus;

    @Column(name = "created_at")
    private java.time.LocalDateTime createdAt;

    @Column(name = "updated_at")
    private java.time.LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = java.time.LocalDateTime.now();
        updatedAt = java.time.LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = java.time.LocalDateTime.now();
    }
}
