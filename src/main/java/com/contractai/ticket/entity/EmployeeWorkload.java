package com.contractai.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("employee_workload")
public class EmployeeWorkload extends TenantBaseEntity {

    private Long employeeId;

    private Integer openTicketsCount;

    private Integer totalTicketsCount;

    private Integer avgResolutionTime;

    private BigDecimal workloadScore;

    private Integer capacity;

    private BigDecimal efficiencyFactor;

    private LocalDateTime lastCalculatedAt;
}
