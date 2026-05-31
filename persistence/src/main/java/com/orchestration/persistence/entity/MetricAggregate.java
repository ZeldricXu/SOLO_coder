package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("metric_aggregate")
public class MetricAggregate extends TenantEntity {

    private Long metricId;

    private String aggregateType;

    private String aggregatePeriod;

    private Long periodStart;

    private Long periodEnd;

    private BigDecimal aggregateValue;

    private Long sampleCount;

    private String labelsJson;
}
