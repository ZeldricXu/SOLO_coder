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

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "metric_snapshots", autoResultMap = true)
public class MetricSnapshot {

    @TableId(type = IdType.INPUT)
    @TableField("snapshot_id")
    private String snapshotId;

    @TableField("timestamp")
    private LocalDateTime timestamp;

    @TableField(value = "metrics", typeHandler = JsonMapTypeHandler.class)
    private Map<String, Object> metrics;

    @TableField(value = "dimensions", typeHandler = JsonMapTypeHandler.class)
    private Map<String, Object> dimensions;

    @TableField("aggregate_level")
    private String aggregateLevel;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
