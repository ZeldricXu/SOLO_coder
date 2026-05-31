package com.scheduler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.scheduler.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notifications")
public class Notification extends BaseEntity {
    private String notificationId;
    private String type;
    private String channel;
    private String recipient;
    private String subject;
    private String content;
    private Map<String, Object> templateParams;
    private String status;
    private Integer retryCount;
    private Integer maxRetries;
    private Instant sentAt;
    private Instant deliveredAt;
    private String errorMessage;
    private String traceId;
    private Map<String, String> metadata;
}
