package com.llmgateway.evaluation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("model_drift")
public class ModelDrift implements Serializable {

    @TableId(value = "drift_id", type = IdType.INPUT)
    private String driftId;

    @TableField("model_id")
    private String modelId;

    @TableField("feature_name")
    private String featureName;

    @TableField("drift_type")
    private String driftType;

    @TableField("drift_score")
    private Double driftScore;

    @TableField("threshold")
    private Double threshold;

    @TableField("is_alert")
    private Boolean isAlert;

    @TableField("window_start")
    private LocalDateTime windowStart;

    @TableField("window_end")
    private LocalDateTime windowEnd;

    @TableField(value = "details", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> details;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
