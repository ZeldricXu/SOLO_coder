package com.crm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "reminders")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Reminder {
    @Id
    @Column(name = "reminder_id", nullable = false, unique = true)
    private String reminderId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "sales_id")
    private String salesId;

    @Column(name = "reminder_type")
    private String reminderType;

    @Column(name = "reminder_time")
    private LocalDateTime reminderTime;

    @Column(name = "reminder_status")
    private String reminderStatus;

    @Column(name = "reminder_content", length = 500)
    private String reminderContent;

    @Column(name = "sent_time")
    private LocalDateTime sentTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (reminderStatus == null) {
            reminderStatus = "pending";
        }
    }
}
