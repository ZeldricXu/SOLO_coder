package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sla_record")
public class SlaRecord extends TenantEntity {

    private Long policyId;

    private Long taskInstanceId;

    private LocalDateTime slaStartTime;

    private LocalDateTime slaEndTime;

    private LocalDateTime actualEndTime;

    private String slaStatus;

    private Integer currentLevel;

    private LocalDateTime warningTime;

    private String escalationHistory;
}
