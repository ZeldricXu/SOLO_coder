package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("metric_definition")
public class MetricDefinition extends TenantEntity {

    private String metricCode;

    private String metricName;

    private String metricType;

    private String unit;

    private String description;

    private String labels;

    private Integer enabled;
}
