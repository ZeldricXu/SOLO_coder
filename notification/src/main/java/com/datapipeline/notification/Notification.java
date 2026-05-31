package com.datapipeline.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    public enum Priority {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    public enum Channel {
        EMAIL,
        SMS,
        SLACK,
        WEBHOOK,
        IN_APP
    }

    public enum Status {
        PENDING,
        SENT,
        FAILED,
        SUPPRESSED
    }

    private String notificationId;
    private String source;
    private String type;
    private String title;
    private String message;
    @Builder.Default
    private Priority priority = Priority.MEDIUM;
    @Builder.Default
    private List<Channel> channels = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> payload = java.util.Collections.emptyMap();
    private String recipientKey;
    @Builder.Default
    private Status status = Status.PENDING;
    @Builder.Default
    private Instant timestamp = Instant.now();
    private Instant sentAt;
    private Duration ttl;
    private String deduplicationKey;
    private int retryCount;
    private int maxRetries;
    private String errorMessage;

}
