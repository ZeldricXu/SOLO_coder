package com.meshcontrol.audit.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "command_log", autoResultMap = true)
public class CommandLog extends BaseEntity {

    private String commandId;
    private String commandType;
    private String aggregateId;
    private String aggregateType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> result;

    private String errorMessage;
    private String executedBy;
    private LocalDateTime executedAt;
    private Long durationMs;
}
