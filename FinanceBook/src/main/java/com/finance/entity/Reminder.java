package com.finance.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "reminders")
public class Reminder {

    @Id
    @Column(name = "reminder_id", nullable = false, length = 50)
    private String reminderId;

    @Column(name = "account_id", nullable = false, length = 50)
    private String accountId;

    @Column(name = "reminder_type", nullable = false, length = 50)
    private String reminderType;

    @Column(name = "reminder_content", nullable = false, length = 500)
    private String reminderContent;

    @Column(name = "reminder_time", nullable = false)
    private LocalDateTime reminderTime;

    @Column(name = "reminder_status", nullable = false, length = 20)
    private String reminderStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
