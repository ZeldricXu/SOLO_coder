package com.metricplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_notification_template")
public class SysNotificationTemplate extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String templateId;

    private String templateName;

    private String channel;

    private String subjectTemplate;

    private String contentTemplate;

    private Map<String, Object> variables;

    private Boolean enabled;
}
