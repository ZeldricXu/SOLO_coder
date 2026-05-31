package com.llmgateway.inference.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("inference_request")
public class InferenceRequest implements Serializable {

    @TableId(value = "request_id", type = IdType.INPUT)
    private String requestId;

    @TableField("model_id")
    private String modelId;

    @TableField("endpoint_id")
    private String endpointId;

    @TableField("provider")
    private String provider;

    @TableField("prompt")
    private String prompt;

    @TableField("max_tokens")
    private Integer maxTokens;

    @TableField("temperature")
    private Double temperature;

    @TableField("top_p")
    private Double topP;

    @TableField("status")
    private String status;

    @TableField("response_text")
    private String responseText;

    @TableField("prompt_tokens")
    private Integer promptTokens;

    @TableField("completion_tokens")
    private Integer completionTokens;

    @TableField("total_tokens")
    private Integer totalTokens;

    @TableField("latency_ms")
    private Long latencyMs;

    @TableField("error_code")
    private String errorCode;

    @TableField("error_message")
    private String errorMessage;

    @TableField("fallback_used")
    private Boolean fallbackUsed;

    @TableField("fallback_reason")
    private String fallbackReason;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;
}
