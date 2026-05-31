package com.solocoder.platform.notification.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String notificationId;
    private String channel;
    private String recipient;
    private NotificationStatus status;
    private String renderedContent;
    private long durationMs;
    private LocalDateTime sentAt;
    private String errorMessage;

    public enum NotificationStatus {
        SENT, FAILED, RATE_LIMITED
    }
}
