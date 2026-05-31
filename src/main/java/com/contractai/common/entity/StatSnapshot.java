package com.contractai.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stat_snapshot")
public class StatSnapshot extends TenantBaseEntity {

    @TableField("snapshot_id")
    private String snapshotId;

    @TableField("snapshot_time")
    private LocalDateTime snapshotTime;

    @TableField(value = "metrics", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> metrics;

    @TableField(value = "dimensions", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, String> dimensions;

    @TableField("metrics_type")
    private String metricsType;

    @TableField("aggregation_level")
    private String aggregationLevel;
}
