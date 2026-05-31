package com.observability.slo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.observability.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_slo_config")
public class SloConfigEntity extends BaseEntity {

    private String sloId;

    private String name;

    private String sliMetric;

    private Double target;

    private Integer timeWindow;

    private Double errorBudget;

    private Double burnRateThreshold;

    private Map<String, Object> notificationConfig;

    private Boolean enabled;
}
