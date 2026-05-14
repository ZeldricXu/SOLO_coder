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
@Table(name = "meetings")
public class Meeting {
    @Id
    @Column(name = "meeting_id", nullable = false, unique = true)
    private String meetingId;

    @Column(name = "room_id", nullable = false)
    private String roomId;

    @Column(name = "meeting_topic", nullable = false)
    private String meetingTopic;

    @Column(name = "meeting_type", nullable = false)
    private String meetingType;

    @Column(name = "meeting_start", nullable = false)
    private LocalDateTime meetingStart;

    @Column(name = "meeting_end", nullable = false)
    private LocalDateTime meetingEnd;

    @Column(name = "meeting_status", nullable = false)
    private String meetingStatus;

    @Column(name = "organizer_id", nullable = false)
    private String organizerId;

    @Column(name = "description")
    private String description;

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
