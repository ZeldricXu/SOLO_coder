package com.logmanager.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
@TableName(value = "slo_config", autoResultMap = true)
public class SLOConfigPO {
    @TableId
    private String id;

    private String sloId;

    private String name;

    private String description;

    private String serviceName;

    private Double targetPercentage;

    private Long windowSeconds;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, String> sliConfig;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> alertingRules;

    private Boolean enabled;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> attributes;

    private Instant createdAt;

    private Instant updatedAt;
}
