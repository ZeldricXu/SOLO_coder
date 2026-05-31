package com.contraudit.transaction.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("signing_policy")
public class SigningPolicy extends BaseEntity {

    private String policyName;

    private String chainType;

    private String policyType;

    private Integer minSignatures;

    private Integer maxSignatures;

    private String signerAddresses;

    private String gasStrategy;

    private BigDecimal customGasMultiplier;

    private String nonceStrategy;

    private Integer status;
}
