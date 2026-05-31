package com.taskflow.data.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskflow.common.model.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("snapshot")
public class SnapshotEntity extends TenantEntity {

    @TableField("snapshot_id")
    private String snapshotId;

    @TableField("timestamp")
    private LocalDateTime timestamp;

    @TableField("metrics")
    private String metrics;

    @TableField("dimensions")
    private String dimensions;

    @TableField("period")
    private String period;

    @TableField(exist = false)
    private Map<String, Object> metricsMap;

    @TableField(exist = false)
    private Map<String, String> dimensionsMap;
}
