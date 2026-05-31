package com.contraudit.transaction.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("transaction_template")
public class TransactionTemplate extends BaseEntity {

    private String templateName;

    private String chainType;

    private String txType;

    private String contractAddress;

    private String methodAbi;

    private String methodName;

    private String parameters;

    private Long gasLimit;

    private BigDecimal gasPrice;

    private BigDecimal value;

    private String description;

    private Integer status;
}
