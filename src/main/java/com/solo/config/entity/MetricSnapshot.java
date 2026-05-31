package com.solo.config.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("metric_snapshots")
public class MetricSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("snapshot_id")
    private String snapshotId;

    private LocalDateTime timestamp;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> metrics;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, String> dimensions;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
