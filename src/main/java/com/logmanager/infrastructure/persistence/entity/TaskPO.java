package com.logmanager.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
@TableName(value = "task", autoResultMap = true)
public class TaskPO {
    @TableId
    private String id;

    private String taskId;

    private String name;

    private String type;

    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> parameters;

    private String scheduledBy;

    private Instant scheduledAt;

    private Instant startedAt;

    private Instant completedAt;

    private Long durationMs;

    private String result;

    private String errorMessage;

    private Integer retryCount;

    private Integer maxRetries;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> attributes;

    private Instant createdAt;

    private Instant updatedAt;
}
