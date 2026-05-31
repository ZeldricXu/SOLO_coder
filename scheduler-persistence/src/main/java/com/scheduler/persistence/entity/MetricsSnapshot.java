package com.scheduler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.scheduler.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("metrics_snapshots")
public class MetricsSnapshot extends BaseEntity {
    private String snapshotId;
    private Instant timestamp;
    private Map<String, Object> metrics;
    private Map<String, String> dimensions;
    private String source;
    private String namespace;
}
