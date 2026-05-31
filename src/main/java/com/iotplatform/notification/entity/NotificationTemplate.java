package com.iotplatform.notification.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.iotplatform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notification_template")
public class NotificationTemplate extends BaseEntity {

    @TableField("template_code")
    private String templateCode;

    @TableField("template_name")
    private String templateName;

    @TableField("channel_type")
    private String channelType;

    @TableField("subject_template")
    private String subjectTemplate;

    @TableField("content_template")
    private String contentTemplate;

    @TableField("variables_schema")
    private String variablesSchema;

    @TableField("enabled")
    private Boolean enabled;
}
