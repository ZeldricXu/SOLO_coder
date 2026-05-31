package com.metricplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_notification_record")
public class SysNotificationRecord extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String recordId;

    private String templateId;

    private String channel;

    private String receiver;

    private String subject;

    private String content;

    private String status;

    private String errorMessage;

    private LocalDateTime sentAt;
}
