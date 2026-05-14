package com.social.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Message {
    @Id
    @Column(name = "message_id", nullable = false, length = 50)
    private String messageId;

    @Column(name = "from_user", nullable = false, length = 50)
    private String fromUser;

    @Column(name = "to_user", nullable = false, length = 50)
    private String toUser;

    @Column(name = "message_type", nullable = false, length = 20)
    private String messageType = "text";

    @Column(name = "message_content", nullable = false, length = 2000)
    private String messageContent;

    @Column(name = "message_status", nullable = false, length = 20)
    private String messageStatus = "sent";

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retry_count", nullable = false)
    private int maxRetryCount = 3;

    @Column(name = "needs_confirmation", nullable = false)
    private boolean needsConfirmation = false;

    @Column(name = "is_confirmed", nullable = false)
    private boolean confirmed = false;

    @Column(name = "last_retry_at")
    private LocalDateTime lastRetryAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (sentAt == null) {
            sentAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Version
    private Long version;
}
