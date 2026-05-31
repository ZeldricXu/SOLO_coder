package com.logmanager.domain.model;

import com.logmanager.common.enums.NotificationPriority;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class Notification extends BaseEntity {
    private String notificationId;
    private String title;
    private String content;
    private NotificationPriority priority;
    private String recipient;
    private String channel;
    private Map<String, Object> payload = new HashMap<>();
    private Instant sentAt;
    private String status;
    private String suppressionKey;
    private Instant suppressedUntil;
}
