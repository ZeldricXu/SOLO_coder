package com.datastandard.common.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "notification_records", autoResultMap = true)
public class NotificationRecord {

    @TableId(type = IdType.INPUT)
    @TableField("notification_id")
    private String notificationId;

    @TableField("channel")
    private String channel;

    @TableField("template_code")
    private String templateCode;

    @TableField("recipient")
    private String recipient;

    @TableField("title")
    private String title;

    @TableField("content")
    private String content;

    @TableField("status")
    private String status;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("error_message")
    private String errorMessage;

    @TableField("sent_at")
    private LocalDateTime sentAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
