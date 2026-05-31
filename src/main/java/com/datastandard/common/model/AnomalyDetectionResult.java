package com.datastandard.common.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datastandard.common.handler.JsonMapTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "anomaly_detection_results", autoResultMap = true)
public class AnomalyDetectionResult {

    @TableId(type = IdType.INPUT)
    @TableField("result_id")
    private String resultId;

    @TableField("metric_name")
    private String metricName;

    @TableField("algorithm")
    private String algorithm;

    @TableField("timestamp")
    private LocalDateTime timestamp;

    @TableField("is_anomaly")
    private Boolean isAnomaly;

    @TableField("anomaly_score")
    private BigDecimal anomalyScore;

    @TableField("baseline_value")
    private BigDecimal baselineValue;

    @TableField("current_value")
    private BigDecimal currentValue;

    @TableField("threshold")
    private BigDecimal threshold;

    @TableField("severity")
    private String severity;

    @TableField(value = "dimensions", typeHandler = JsonMapTypeHandler.class)
    private Map<String, Object> dimensions;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
