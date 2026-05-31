package com.iotplatform.notification.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("notification")
public class Notification implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("notification_id")
    private String notificationId;

    @TableField("template_code")
    private String templateCode;

    @TableField("channel_type")
    private String channelType;

    @TableField("recipient")
    private String recipient;

    @TableField("subject")
    private String subject;

    @TableField("content")
    private String content;

    @TableField("variables")
    private String variables;

    @TableField("status")
    private String status;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("max_retries")
    private Integer maxRetries;

    @TableField("sent_at")
    private LocalDateTime sentAt;

    @TableField("error_detail")
    private String errorDetail;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public interface ChannelType {
        String SMS = "sms";
        String EMAIL = "email";
        String WEBHOOK = "webhook";
        String APP = "app";
        String DINGTALK = "dingtalk";
        String WECHAT = "wechat";
    }

    public interface Status {
        String PENDING = "pending";
        String SENDING = "sending";
        String SENT = "sent";
        String FAILED = "failed";
    }
}
