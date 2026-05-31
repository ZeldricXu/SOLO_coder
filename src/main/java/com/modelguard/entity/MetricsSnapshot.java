package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "metrics_snapshot", autoResultMap = true)
public class MetricsSnapshot extends BaseEntity {

    @TableField("snapshot_id")
    private String snapshotId;

    @TableField("timestamp")
    private LocalDateTime timestamp;

    @TableField(value = "metrics", typeHandler = JacksonTypeHandler.class)
    private ObjectNode metrics;

    @TableField(value = "dimensions", typeHandler = JacksonTypeHandler.class)
    private ObjectNode dimensions;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
