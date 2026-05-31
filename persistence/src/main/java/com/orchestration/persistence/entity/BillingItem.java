package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("billing_item")
public class BillingItem extends TenantEntity {

    private Long cycleId;

    private String resourceType;

    private Long usageAmount;

    private BigDecimal unitPrice;

    private BigDecimal totalPrice;

    private String unit;
}
