package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("traffic_strategy_run")
public class TrafficStrategyRun extends BaseEntity {

    private String runId;
    private String strategyId;
    private String phase;
    private BigDecimal progress;
    private Integer trafficPercentage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Map<String, Object> metrics;
    private String errorDetail;
}
