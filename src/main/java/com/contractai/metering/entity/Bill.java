package com.contractai.metering.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bill")
public class Bill extends TenantBaseEntity {

    @TableField("bill_no")
    private String billNo;

    @TableField("billing_period")
    private String billingPeriod;

    @TableField("total_amount")
    private BigDecimal totalAmount;

    @TableField("paid_amount")
    private BigDecimal paidAmount;

    @TableField("status")
    private String status;

    @TableField("issue_date")
    private LocalDate issueDate;

    @TableField("due_date")
    private LocalDate dueDate;

    @TableField("paid_date")
    private LocalDate paidDate;

    @TableField("currency")
    private String currency;

    @TableField(value = "bill_items", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Map<String, Object>> billItems;

    @TableField(value = "summary", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> summary;
}
