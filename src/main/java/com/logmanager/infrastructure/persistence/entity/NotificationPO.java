package com.logmanager.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
@TableName(value = "notification", autoResultMap = true)
public class NotificationPO {
    @TableId
    private String id;

    private String notificationId;

    private String title;

    private String content;

    private String priority;

    private String recipient;

    private String channel;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    private Instant sentAt;

    private String status;

    private String suppressionKey;

    private Instant suppressedUntil;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> attributes;

    private Instant createdAt;

    private Instant updatedAt;
}
