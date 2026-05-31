package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("billing_cycle")
public class BillingCycle extends TenantEntity {

    private String cycleType;

    private String cycleCode;

    private LocalDate cycleStart;

    private LocalDate cycleEnd;

    private BigDecimal totalAmount;

    private String status;

    private LocalDateTime paidAt;
}
