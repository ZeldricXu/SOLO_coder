package com.solo.config.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notifications")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("notification_id")
    private String notificationId;

    private String type;

    private Integer priority;

    private String title;

    private String content;

    private String recipient;

    private String status;

    @TableField("sent_at")
    private LocalDateTime sentAt;

    @TableField("error_message")
    private String errorMessage;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
