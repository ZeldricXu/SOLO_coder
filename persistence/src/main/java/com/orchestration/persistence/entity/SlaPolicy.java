package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sla_policy")
public class SlaPolicy extends TenantEntity {

    private String policyName;

    private String policyCode;

    private String taskType;

    private Long slaDuration;

    private BigDecimal warningThreshold;

    private String escalationLevels;

    private String notificationChannels;

    private Integer enabled;
}
