package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("metric_data")
public class MetricData extends TenantEntity {

    private Long metricId;

    private BigDecimal metricValue;

    private String labelsJson;

    private Long timestampMs;
}
