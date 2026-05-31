package com.llmgateway.adversarial.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("adversarial_evaluation")
public class AdversarialEvaluation implements Serializable {

    @TableId(value = "eval_id", type = IdType.INPUT)
    private String evalId;

    @TableField("model_id")
    private String modelId;

    @TableField("model_version")
    private String modelVersion;

    @TableField("attack_count")
    private Integer attackCount;

    @TableField("success_count")
    private Integer successCount;

    @TableField("failure_count")
    private Integer failureCount;

    @TableField("success_rate")
    private Double successRate;

    @TableField("avg_response_time_ms")
    private Long avgResponseTimeMs;

    @TableField(value = "details", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> details;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
