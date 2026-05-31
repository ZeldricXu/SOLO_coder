package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_billing_invoice")
public class BillingInvoice extends BaseEntity {

    private String invoiceNo;
    private Long tenantId;
    private String tenantName;
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private Integer status;
    private LocalDateTime billingPeriodStart;
    private LocalDateTime billingPeriodEnd;
    private LocalDateTime dueDate;
    private LocalDateTime paidAt;
    private String items;
    private String remark;
}
