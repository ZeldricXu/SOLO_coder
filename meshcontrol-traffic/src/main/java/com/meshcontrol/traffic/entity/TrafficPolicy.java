package com.meshcontrol.traffic.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "traffic_policy", autoResultMap = true)
public class TrafficPolicy extends BaseEntity {

    private String policyId;
    private String name;
    private String type;
    private String namespace;
    private String serviceName;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> matchRules;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> routes;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> mirrorConfig;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> circuitBreaker;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> retryPolicy;

    private Integer timeoutMs;
    private Boolean enabled;
    private Integer priority;
}
