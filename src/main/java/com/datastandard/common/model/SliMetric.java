package com.datastandard.common.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "sli_metrics", autoResultMap = true)
public class SliMetric {

    @TableId(type = IdType.INPUT)
    @TableField("metric_id")
    private String metricId;

    @TableField("slo_id")
    private String sloId;

    @TableField("timestamp")
    private LocalDateTime timestamp;

    @TableField("sli_value")
    private BigDecimal sliValue;

    @TableField("good_events")
    private Long goodEvents;

    @TableField("total_events")
    private Long totalEvents;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
