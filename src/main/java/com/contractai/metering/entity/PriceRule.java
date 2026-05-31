package com.contractai.metering.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("price_rule")
public class PriceRule extends TenantBaseEntity {

    @TableField("resource_type")
    private String resourceType;

    @TableField("billing_mode")
    private String billingMode;

    @TableField("price_per_unit")
    private BigDecimal pricePerUnit;

    @TableField(value = "tier_config", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Map<String, Object>> tierConfig;

    @TableField("currency")
    private String currency;

    @TableField("effective_from")
    private LocalDateTime effectiveFrom;

    @TableField("effective_to")
    private LocalDateTime effectiveTo;
}
