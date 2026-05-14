package com.meeting.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "meeting_stats")
public class MeetingStats {
    @Id
    @Column(name = "stat_id", nullable = false, unique = true)
    private String statId;

    @Column(name = "stat_month", nullable = false, unique = true)
    private String statMonth;

    @Column(name = "meeting_count", nullable = false)
    private Integer meetingCount;

    @Column(name = "total_duration_minutes")
    private Long totalDurationMinutes;

    @Column(name = "attendee_count")
    private Integer attendeeCount;

    @Column(name = "confirmed_attendee_count")
    private Integer confirmedAttendeeCount;

    @Column(name = "reminder_sent_count")
    private Integer reminderSentCount;

    @Column(name = "cancelled_count")
    private Integer cancelledCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (meetingCount == null) meetingCount = 0;
        if (totalDurationMinutes == null) totalDurationMinutes = 0L;
        if (attendeeCount == null) attendeeCount = 0;
        if (confirmedAttendeeCount == null) confirmedAttendeeCount = 0;
        if (reminderSentCount == null) reminderSentCount = 0;
        if (cancelledCount == null) cancelledCount = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
