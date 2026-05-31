package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aggregation_window")
public class AggregationWindow extends BaseEntity {
    private String windowId;
    private String deviceId;
    private String metricName;
    private Long windowStartMs;
    private Long windowEndMs;
    private Long windowSizeMs;
    private Long recordCount;
    private Double minValue;
    private Double maxValue;
    private Double avgValue;
    private Double sumValue;
    private Double variance;
    private Double stdDev;
    private String percentiles;
    private Instant aggregatedAt;
    private boolean uploaded;
}
