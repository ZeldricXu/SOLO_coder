package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sla_policy")
public class SlaPolicy extends BaseEntity {

    private String policyCode;
    private String policyName;
    private String relatedType;
    private Long responseTime;
    private Long resolutionTime;
    private Long warningTime;
    private Integer escalationLevel;
    private String escalationRules;
    private Integer enabled;
    private String description;
}
