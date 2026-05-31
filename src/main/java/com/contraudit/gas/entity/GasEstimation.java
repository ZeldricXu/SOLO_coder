package com.contraudit.gas.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gas_estimation")
public class GasEstimation extends BaseEntity {

    private String estimationId;

    private String chainType;

    private String networkId;

    private String txType;

    private BigDecimal estimatedGasLimit;

    private BigDecimal slowGasPrice;

    private BigDecimal standardGasPrice;

    private BigDecimal fastGasPrice;

    private BigDecimal slowPriorityFee;

    private BigDecimal standardPriorityFee;

    private BigDecimal fastPriorityFee;

    private BigDecimal baseFee;

    private BigDecimal slowEstimatedCost;

    private BigDecimal standardEstimatedCost;

    private BigDecimal fastEstimatedCost;

    private Integer confidenceLevel;

    private Integer sampleSize;

    private String predictionModel;

    private Long fromBlock;

    private Long toBlock;

    private LocalDateTime estimatedAt;

    private LocalDateTime expiresAt;
}
