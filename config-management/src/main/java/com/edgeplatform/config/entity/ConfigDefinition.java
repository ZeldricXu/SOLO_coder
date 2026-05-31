package com.edgeplatform.config.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.edgeplatform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "config_definition", autoResultMap = true)
public class ConfigDefinition extends BaseEntity {

    private String configId;

    private String namespace;

    private Integer version;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> parameters;

    private Boolean enabled;

    private LocalDateTime appliedAt;

    private String description;

    private String changeLog;
}
