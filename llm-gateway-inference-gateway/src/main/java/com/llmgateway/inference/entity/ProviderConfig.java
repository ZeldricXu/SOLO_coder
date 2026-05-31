package com.llmgateway.inference.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("provider_config")
public class ProviderConfig implements Serializable {

    @TableId(value = "provider_id", type = IdType.INPUT)
    private String providerId;

    @TableField("provider_name")
    private String providerName;

    @TableField("base_url")
    private String baseUrl;

    @TableField("api_key")
    private String apiKey;

    @TableField("api_type")
    private String apiType;

    @TableField("timeout")
    private Integer timeout;

    @TableField("max_retries")
    private Integer maxRetries;

    @TableField("rate_limit")
    private Integer rateLimit;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("priority")
    private Integer priority;

    @TableField("load_balancer")
    private String loadBalancer;

    @TableField("circuit_breaker_enabled")
    private Boolean circuitBreakerEnabled;

    @TableField("failure_threshold")
    private Integer failureThreshold;

    @TableField("fallback_enabled")
    private Boolean fallbackEnabled;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
