package com.taskflow.notification.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class NotificationResult {
    private String recordId;
    private String templateId;
    private String type;
    private String channel;
    private List<String> receivers;
    private String status;
    private String errorMessage;
    private LocalDateTime sentAt;
    private long durationMs;
}
