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
@Table(name = "meeting_history")
public class MeetingHistory {
    @Id
    @Column(name = "history_id", nullable = false, unique = true)
    private String historyId;

    @Column(name = "meeting_id")
    private String meetingId;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Column(name = "action_detail")
    private String actionDetail;

    @Column(name = "operator_id")
    private String operatorId;

    @Column(name = "room_id")
    private String roomId;

    @Column(name = "meeting_topic")
    private String meetingTopic;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
