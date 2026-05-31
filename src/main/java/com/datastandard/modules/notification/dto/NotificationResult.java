package com.datastandard.modules.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResult {

    private String recordId;

    private String channel;

    private String recipient;

    private boolean success;

    private String errorMessage;

    private String traceId;

    private int retryAttempt;

    private long durationMs;

    private Instant sentAt;

    private Map<String, Object> metadata;

    public static NotificationResult success(String channel, String recipient, long durationMs) {
        return NotificationResult.builder()
                .channel(channel)
                .recipient(recipient)
                .success(true)
                .durationMs(durationMs)
                .sentAt(Instant.now())
                .build();
    }

    public static NotificationResult failure(String channel, String recipient,
                                             String errorMessage, int retryAttempt) {
        return NotificationResult.builder()
                .channel(channel)
                .recipient(recipient)
                .success(false)
                .errorMessage(errorMessage)
                .retryAttempt(retryAttempt)
                .sentAt(Instant.now())
                .build();
    }
}
