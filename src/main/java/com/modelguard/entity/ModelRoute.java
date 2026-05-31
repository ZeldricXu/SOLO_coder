package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "model_route", autoResultMap = true)
public class ModelRoute extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String routeId;

    private String routeName;

    private String modelName;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> primaryProviders;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> fallbackProviders;

    private String loadBalanceStrategy;

    private Boolean enableFallback;

    private Integer timeoutMs;

    private Integer maxRetries;

    private Double failureThreshold;

    private Integer circuitBreakerOpenMs;

    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> routingRules;

    private String description;
}
