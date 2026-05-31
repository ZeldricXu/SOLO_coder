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
@TableName("alert_rules")
public class AlertRuleDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String ruleId;

    private String name;

    private String description;

    private String namespace;

    private String metricName;

    private String operator;

    private Double threshold;

    private Integer durationSeconds;

    private String severity;

    private String notificationChannels;

    private String labels;

    private String annotations;

    private Boolean enabled;

    private String createdBy;

    private Instant createdAt;

    private Instant updatedAt;
}
