package com.logmanager.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
@TableName(value = "log_entry", autoResultMap = true)
public class LogEntryPO {
    @TableId
    private String id;

    private String traceId;

    private String serviceName;

    private String level;

    private String message;

    private String loggerName;

    private String threadName;

    private Instant timestamp;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, String> tags;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> attributes;

    private Instant createdAt;

    private Instant updatedAt;
}
