package com.datastandard.modules.slo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sli_metrics")
public class SliMetric {

    @TableId(type = IdType.ASSIGN_UUID)
    private String metricId;

    private String sloId;

    private String sliType;

    private Instant windowStart;

    private Instant windowEnd;

    private Double sliValue;

    private Long totalEvents;

    private Long goodEvents;

    private Long badEvents;

    private String labels;

    private String aggregation;

    private Instant createdAt;

    private Integer deleted;
}
