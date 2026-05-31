package com.llmgateway.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("metric_snapshot")
public class MetricSnapshot {

    @TableId(value = "snapshot_id", type = IdType.ASSIGN_ID)
    private String snapshotId;

    @TableField("timestamp")
    private LocalDateTime timestamp;

    @TableField(value = "metrics", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> metrics;

    @TableField(value = "dimensions", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, String> dimensions;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
