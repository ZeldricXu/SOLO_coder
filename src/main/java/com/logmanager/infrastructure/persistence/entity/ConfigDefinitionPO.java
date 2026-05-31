package com.logmanager.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
@TableName(value = "config_definition", autoResultMap = true)
public class ConfigDefinitionPO {
    @TableId
    private String id;

    private String configId;

    private String namespace;

    private Integer version;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> parameters;

    private Boolean enabled;

    private Instant appliedAt;

    private String source;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> attributes;

    private Instant createdAt;

    private Instant updatedAt;
}
