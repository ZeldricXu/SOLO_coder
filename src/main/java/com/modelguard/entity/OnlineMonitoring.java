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
@TableName(value = "online_monitoring", autoResultMap = true)
public class OnlineMonitoring extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String monitorId;

    private String modelId;

    private String version;

    private LocalDateTime timestamp;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metrics;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> predictionDistribution;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> featureDistribution;

    private Long requestCount;

    private Long successCount;

    private Long errorCount;

    private Double avgLatencyMs;

    private Double p50LatencyMs;

    private Double p95LatencyMs;

    private Double p99LatencyMs;

    private Double throughput;

    private Double errorRate;

    private String timeWindow;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> alerts;
}
