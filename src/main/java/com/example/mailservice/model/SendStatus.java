package com.example.mailservice.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "send_status")
public class SendStatus {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "status_id", unique = true, nullable = false, length = 64)
    private String statusId;

    @Column(name = "mail_id", nullable = false, length = 64)
    private String mailId;

    @Column(name = "send_status", nullable = false, length = 32)
    private String sendStatus;

    @Column(name = "smtp_response", columnDefinition = "TEXT")
    private String smtpResponse;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "send_attempts")
    private Integer sendAttempts;

    @Column(name = "last_attempt", nullable = false)
    private LocalDateTime lastAttempt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (sendAttempts == null) {
            sendAttempts = 1;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
