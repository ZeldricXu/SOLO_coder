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
@TableName(value = "drift_detection", autoResultMap = true)
public class DriftDetection extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String detectionId;

    private String modelId;

    private String version;

    private String driftType;

    private String featureName;

    private Double driftScore;

    private String driftStatus;

    private Double threshold;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> baselineDistribution;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> currentDistribution;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> statisticalTests;

    private LocalDateTime detectionTime;

    private String timeWindow;

    private String severity;

    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> recommendedActions;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;
}
