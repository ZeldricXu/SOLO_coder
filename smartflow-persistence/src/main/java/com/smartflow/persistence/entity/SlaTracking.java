package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sla_tracking")
public class SlaTracking extends BaseEntity {

    private Long policyId;
    private String policyName;
    private Long relatedId;
    private String relatedType;
    private Integer slaStatus;
    private LocalDateTime startTime;
    private LocalDateTime deadline;
    private LocalDateTime warningTime;
    private Long remainingTime;
    private Integer escalationLevel;
    private LocalDateTime lastEscalatedAt;
    private String escalationHistory;
    private String relatedData;
}
