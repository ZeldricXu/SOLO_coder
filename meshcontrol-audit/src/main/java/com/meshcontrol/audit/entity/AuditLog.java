package com.meshcontrol.audit.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "audit_log", autoResultMap = true)
public class AuditLog extends BaseEntity {

    private String auditId;
    private String commandId;
    private String eventId;
    private String action;
    private String resourceType;
    private String resourceId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> oldValue;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> newValue;

    private String operator;
    private String sourceIp;
    private String userAgent;
}
