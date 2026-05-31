package com.edgescheduler.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("config")
public class ConfigEntity extends BaseEntity {

    @TableField("config_id")
    private String configId;

    @TableField("namespace")
    private String namespace;

    @TableField("version")
    private Integer version;

    @TableField(value = "parameters", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> parameters;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("applied_at")
    private LocalDateTime appliedAt;
}
