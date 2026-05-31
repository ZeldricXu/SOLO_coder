package com.datastandard.modules.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("notification_records")
public class NotificationRecord {

    @TableId(type = IdType.ASSIGN_UUID)
    private String recordId;

    private String traceId;

    private String type;

    private String channel;

    private String recipient;

    private String sender;

    private String templateCode;

    private String subject;

    private String content;

    private String status;

    private String errorMessage;

    private int priority;

    private int retryCount;

    private int maxRetries;

    private long durationMs;

    private Instant scheduledTime;

    private Instant sentAt;

    private Instant createdAt;

    private Integer deleted;
}
