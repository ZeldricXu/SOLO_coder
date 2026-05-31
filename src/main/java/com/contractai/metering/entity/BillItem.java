package com.contractai.metering.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bill_item")
public class BillItem extends TenantBaseEntity {

    @TableField("bill_id")
    private Long billId;

    @TableField("resource_type")
    private String resourceType;

    @TableField("usage_amount")
    private Long usageAmount;

    @TableField("unit")
    private String unit;

    @TableField("unit_price")
    private BigDecimal unitPrice;

    @TableField("amount")
    private BigDecimal amount;

    @TableField("description")
    private String description;
}
