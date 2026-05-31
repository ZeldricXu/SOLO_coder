package com.delivery.tracker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.delivery.tracker.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notification")
public class Notification extends BaseEntity {

    private String notificationId;

    private String type;

    private String recipient;

    private String content;

    private String status;

    private Integer retryCount;

    private Integer maxRetries;

    private LocalDateTime nextRetryAt;

    private String lastError;

    private LocalDateTime deliveredAt;
}
