package com.edgescheduler.modules.shadow.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("device_shadow")
public class DeviceShadow extends BaseEntity {

    @TableField("device_id")
    private String deviceId;

    @TableField("shadow_version")
    private Integer shadowVersion;

    @TableField(value = "desired_state", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> desiredState;

    @TableField(value = "reported_state", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> reportedState;

    @TableField(value = "delta_state", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> deltaState;

    @TableField("last_sync_time")
    private LocalDateTime lastSyncTime;

    @TableField("sync_status")
    private String syncStatus;

    @TableField("sync_latency_ms")
    private Long syncLatencyMs;

    @TableField("conflict_count")
    private Integer conflictCount;

    @TableField("last_conflict_time")
    private LocalDateTime lastConflictTime;

    @TableField("monitor_status")
    private String monitorStatus;

    @TableField("last_metric_update")
    private LocalDateTime lastMetricUpdate;
}
