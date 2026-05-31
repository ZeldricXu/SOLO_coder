package com.meshcontrol.eventstore.entity;

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
@TableName(value = "event_log", autoResultMap = true)
public class EventLog extends BaseEntity {

    private String eventId;
    private String aggregateId;
    private String aggregateType;
    private String eventType;
    private Integer version;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    private String source;
    private LocalDateTime createdAt;
}
