package com.dynamiclog.common.entity;

import com.dynamiclog.common.enums.NotificationStatus;
import com.dynamiclog.common.enums.NotificationType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class Notification extends BaseEntity {
    private String templateId;
    private NotificationType type;
    private NotificationStatus status;
    private String recipient;
    private String subject;
    private String content;
    private Map<String, Object> variables;
    private String channel;
    private Integer priority = 0;
    private Integer maxRetries = 3;
    private Integer retryCount = 0;
    private LocalDateTime sentAt;
    private LocalDateTime deliveredAt;
    private String deliveryReceipt;
    private String errorMessage;
    private Long ttlSeconds;
    private LocalDateTime expiresAt;
    private String traceId;
    private Map<String, String> metadata;
}
