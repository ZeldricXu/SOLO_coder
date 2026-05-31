package com.didauth.module.gas.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

@Data
public class GasEstimateResponse implements Serializable {

    private String chainType;
    private String priorityLevel;
    private String gasPrice;
    private String maxFeePerGas;
    private String maxPriorityFeePerGas;
    private String baseFee;
    private String estimatedGasLimit;
    private BigDecimal estimatedUsdCost;
    private Long timestamp;
    private Map<String, String> historicalTrend;
}
