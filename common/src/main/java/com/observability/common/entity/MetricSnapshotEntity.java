package com.observability.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_metric_snapshot")
public class MetricSnapshotEntity extends BaseEntity {

    private String snapshotId;

    private LocalDateTime timestamp;

    private Map<String, Object> metrics;

    private Map<String, String> dimensions;

    private String metricName;

    private Double value;
}
