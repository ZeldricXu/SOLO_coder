package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "evaluation_metric", autoResultMap = true)
public class EvaluationMetric extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String evaluationId;

    private String modelId;

    private String version;

    private String evaluationType;

    private String datasetName;

    private String datasetVersion;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metrics;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metricDetails;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> comparisonResults;

    private String baselineModelId;

    private String baselineVersion;

    private String status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private String evaluatedBy;

    private String notes;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;
}
