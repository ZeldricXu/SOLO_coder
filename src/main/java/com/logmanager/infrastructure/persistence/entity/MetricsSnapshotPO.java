package com.logmanager.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
@TableName(value = "metrics_snapshot", autoResultMap = true)
public class MetricsSnapshotPO {
    @TableId
    private String id;

    private String snapshotId;

    private Instant timestamp;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Double> metrics;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, String> dimensions;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> attributes;

    private Instant createdAt;

    private Instant updatedAt;
}
