package com.taskflow.data.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskflow.common.model.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("config")
public class ConfigEntity extends TenantEntity {

    @TableField("config_id")
    private String configId;

    @TableField("namespace")
    private String namespace;

    @TableField("version")
    private Integer version;

    @TableField("parameters")
    private String parameters;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("applied_at")
    private LocalDateTime appliedAt;

    @TableField("description")
    private String description;

    @TableField(exist = false)
    private Map<String, Object> parametersMap;
}
