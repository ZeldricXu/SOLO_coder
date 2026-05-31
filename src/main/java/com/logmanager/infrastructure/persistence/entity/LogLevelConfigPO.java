package com.logmanager.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
@TableName(value = "log_level_config", autoResultMap = true)
public class LogLevelConfigPO {
    @TableId
    private String id;

    private String serviceName;

    private String loggerName;

    private String currentLevel;

    private String targetLevel;

    private Instant effectiveAt;

    private Instant expiresAt;

    private String reason;

    private String operator;

    private Boolean active;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> attributes;

    private Instant createdAt;

    private Instant updatedAt;
}
