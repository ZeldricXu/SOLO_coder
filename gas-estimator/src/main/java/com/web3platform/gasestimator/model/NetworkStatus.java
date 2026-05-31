package com.web3platform.gasestimator.model;

import lombok.Data;

@Data
public class NetworkStatus {

    private String chainId;
    private Integer pendingTxCount;
    private Long blockGasUsed;
    private Long blockGasLimit;
    private String congestionLevel;
    private String baseFeeTrend;
}
