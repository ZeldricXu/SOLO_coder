package com.web3platform.gasestimator.model;

import lombok.Data;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;

@Data
public class GasEstimateResponse {

    private BigInteger gasPrice;
    private BigInteger baseFee;
    private BigInteger priorityFee;
    private BigInteger maxFeePerGas;
    private BigInteger maxPriorityFeePerGas;
    private BigDecimal estimatedCost;
    private Long gasLimit;
    private double confidence;
    private LocalDateTime timestamp;
}
