package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_gas_estimate")
public class GasEstimate extends BaseEntity {

    private String chainType;
    private String priorityLevel;
    private String gasPrice;
    private String maxFeePerGas;
    private String maxPriorityFeePerGas;
    private String baseFee;
    private String estimatedGasLimit;
    private BigDecimal estimatedUsdCost;
    private Long timestamp;
}
