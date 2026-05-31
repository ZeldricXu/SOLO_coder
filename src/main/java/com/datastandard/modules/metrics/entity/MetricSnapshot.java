package com.datastandard.modules.metrics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("metric_snapshots")
public class MetricSnapshot {

    @TableId(type = IdType.ASSIGN_UUID)
    private String snapshotId;

    private Instant timestamp;

    private String metrics;

    private String dimensions;

    private String aggregateLevel;

    private Instant createdAt;
}
