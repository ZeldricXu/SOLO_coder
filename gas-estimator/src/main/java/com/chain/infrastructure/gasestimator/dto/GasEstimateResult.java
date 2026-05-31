package com.chain.infrastructure.gasestimator.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class GasEstimateResult {

    private String chainType;

    private BigDecimal slowGasPrice;

    private BigDecimal standardGasPrice;

    private BigDecimal fastGasPrice;

    private BigDecimal baseFee;

    private BigDecimal priorityFee;

    private Long estimatedGasLimit;

    private BigDecimal estimatedSlowFee;

    private BigDecimal estimatedStandardFee;

    private BigDecimal estimatedFastFee;

    private Long timestamp;
}
