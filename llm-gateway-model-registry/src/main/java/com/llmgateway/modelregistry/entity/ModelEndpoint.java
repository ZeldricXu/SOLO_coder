package com.llmgateway.modelregistry.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("model_endpoint")
public class ModelEndpoint implements Serializable {

    @TableId(value = "endpoint_id", type = IdType.INPUT)
    private String endpointId;

    @TableField("model_id")
    private String modelId;

    @TableField("version_id")
    private String versionId;

    @TableField("endpoint_name")
    private String endpointName;

    @TableField("provider")
    private String provider;

    @TableField("base_url")
    private String baseUrl;

    @TableField("api_key")
    private String apiKey;

    @TableField("max_tokens")
    private Integer maxTokens;

    @TableField("temperature")
    private Double temperature;

    @TableField("timeout")
    private Integer timeout;

    @TableField("rate_limit")
    private Integer rateLimit;

    @TableField("status")
    private String status;

    @TableField("priority")
    private Integer priority;

    @TableField("weight")
    private Integer weight;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
