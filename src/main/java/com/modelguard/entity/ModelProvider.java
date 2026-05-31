package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "model_provider", autoResultMap = true)
public class ModelProvider extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String providerId;

    private String providerName;

    private String providerType;

    private String baseUrl;

    private String apiKey;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> config;

    private Integer weight;

    private Integer priority;

    private String status;

    private Integer timeoutMs;

    private Integer maxRetries;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> supportedModels;

    private String healthCheckEndpoint;

    private Long lastHealthCheckAt;

    private String healthStatus;

    private Double successRate;

    private Double avgLatencyMs;
}
