package com.llmgateway.evaluation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("evaluation_run")
public class EvaluationRun implements Serializable {

    @TableId(value = "run_id", type = IdType.INPUT)
    private String runId;

    @TableField("run_name")
    private String runName;

    @TableField("model_id")
    private String modelId;

    @TableField("model_version")
    private String modelVersion;

    @TableField("dataset")
    private String dataset;

    @TableField("evaluation_type")
    private String evaluationType;

    @TableField("status")
    private String status;

    @TableField(value = "metrics", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> metrics;

    @TableField(value = "results", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> results;

    @TableField("error_detail")
    private String errorDetail;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
