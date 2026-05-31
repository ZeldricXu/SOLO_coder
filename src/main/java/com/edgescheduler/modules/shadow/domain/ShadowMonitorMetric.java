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
@TableName("shadow_monitor_metric")
public class ShadowMonitorMetric extends BaseEntity {

    @TableField("metric_id")
    private String metricId;

    @TableField("device_id")
    private String deviceId;

    @TableField("metric_type")
    private String metricType;

    @TableField("metric_value")
    private Double metricValue;

    @TableField("metric_unit")
    private String metricUnit;

    @TableField("timestamp")
    private LocalDateTime timestamp;

    @TableField(value = "tags", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> tags;
}
