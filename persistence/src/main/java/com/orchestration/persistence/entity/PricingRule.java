package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pricing_rule")
public class PricingRule extends BaseEntity {

    private String resourceType;

    private String billingMode;

    private BigDecimal unitPrice;

    private String unit;

    private String currency;

    private String tierConfig;

    private LocalDate effectiveDate;

    private LocalDate expiryDate;

    private Integer enabled;
}
