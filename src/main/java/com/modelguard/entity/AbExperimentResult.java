package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ab_experiment_result", autoResultMap = true)
public class AbExperimentResult extends BaseEntity {

    @TableField("experiment_id")
    private String experimentId;

    @TableField("group_type")
    private String groupType;

    @TableField("total_requests")
    private Long totalRequests;

    @TableField("success_count")
    private Long successCount;

    @TableField("avg_latency_ms")
    private BigDecimal avgLatencyMs;

    @TableField("p99_latency_ms")
    private BigDecimal p99LatencyMs;

    @TableField("error_rate")
    private BigDecimal errorRate;

    @TableField("satisfaction_score")
    private BigDecimal satisfactionScore;

    @TableField(value = "metrics", typeHandler = JacksonTypeHandler.class)
    private ObjectNode metrics;

    @TableField("snapshot_time")
    private LocalDateTime snapshotTime;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
