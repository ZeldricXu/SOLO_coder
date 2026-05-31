package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tenant")
public class Tenant extends BaseEntity {

    private String tenantCode;
    private String tenantName;
    private String contactPerson;
    private String contactEmail;
    private String contactPhone;
    private String address;
    private Integer status;
    private String industry;
    private BigDecimal accountBalance;
    private String billingCycle;
    private String configJson;
    private String remark;
}
