package com.monitoring.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("metric_data")
public class MetricDataDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String metricName;

    private Double value;

    private String dimensions;

    private Instant timestamp;

    private Long timestampHour;

    private Long timestampDay;

    private Instant createdAt;
}
