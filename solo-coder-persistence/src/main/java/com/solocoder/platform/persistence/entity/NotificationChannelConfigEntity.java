package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_notification_channel_config")
public class NotificationChannelConfigEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String channelType;
    private Boolean enabled;
    private Integer rateLimitPerSecond;
    private Integer timeoutMs;
    private Integer maxRetries;
    private Long retryIntervalMs;
    private String extraJson;
    private Long configVersion;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
