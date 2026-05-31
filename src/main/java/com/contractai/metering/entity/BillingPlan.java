package com.contractai.metering.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("billing_plan")
public class BillingPlan extends TenantBaseEntity {

    @TableField("plan_code")
    private String planCode;

    @TableField("plan_name")
    private String planName;

    @TableField("plan_type")
    private String planType;

    @TableField("price")
    private BigDecimal price;

    @TableField("billing_cycle")
    private String billingCycle;

    @TableField("start_date")
    private LocalDate startDate;

    @TableField("end_date")
    private LocalDate endDate;

    @TableField("status")
    private Integer status;

    @TableField(value = "included_resources", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> includedResources;

    @TableField(value = "overage_rates", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> overageRates;
}
