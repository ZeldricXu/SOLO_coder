package com.observability.alert.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.observability.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_alert_rule")
public class AlertRuleEntity extends BaseEntity {

    private String alertId;

    private String name;

    private String metricName;

    private String expression;

    private String level;

    private Double threshold;

    private Integer duration;

    private Map<String, Object> notificationConfig;

    private Boolean enabled;
}
