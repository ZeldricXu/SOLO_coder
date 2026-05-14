package com.survey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "answer_reminder_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnswerReminderRecord {

    @Id
    @Column(name = "reminder_id", nullable = false, length = 50)
    private String reminderId;

    @Column(name = "survey_id", nullable = false, length = 50)
    private String surveyId;

    @Column(name = "publish_id", nullable = false, length = 50)
    private String publishId;

    @Column(name = "user_id", length = 50)
    private String userId;

    @Column(name = "user_email", length = 200)
    private String userEmail;

    @Column(name = "reminder_status", nullable = false, length = 30)
    private String reminderStatus;

    @Column(name = "reminder_count", nullable = false)
    private Integer reminderCount = 0;

    @Column(name = "max_reminder_count", nullable = false)
    private Integer maxReminderCount = 3;

    @Column(name = "last_reminder_time")
    private LocalDateTime lastReminderTime;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
