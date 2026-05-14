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
@Table(name = "reminders")
public class Reminder {
    @Id
    @Column(name = "reminder_id", nullable = false, unique = true)
    private String reminderId;

    @Column(name = "meeting_id", nullable = false)
    private String meetingId;

    @Column(name = "reminder_type", nullable = false)
    private String reminderType;

    @Column(name = "reminder_time", nullable = false)
    private LocalDateTime reminderTime;

    @Column(name = "reminder_status", nullable = false)
    private String reminderStatus;

    @Column(name = "sent_time")
    private LocalDateTime sentTime;

    @Column(name = "reminder_content")
    private String reminderContent;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
