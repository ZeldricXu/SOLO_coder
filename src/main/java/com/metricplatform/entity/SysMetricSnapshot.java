package com.metricplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_metric_snapshot")
public class SysMetricSnapshot extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String snapshotId;

    private LocalDateTime timestamp;

    private Map<String, Object> metrics;

    private Map<String, Object> dimensions;

    private String source;

    private Long interval;
}
