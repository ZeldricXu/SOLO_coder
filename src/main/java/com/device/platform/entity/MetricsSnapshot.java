package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("metrics_snapshot")
public class MetricsSnapshot extends BaseEntity {
    private String snapshotId;
    private Instant timestamp;
    private String metrics;
    private String dimensions;
    private String metricType;
    private Long windowSizeMs;
}
