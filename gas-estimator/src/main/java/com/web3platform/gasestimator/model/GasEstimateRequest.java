package com.web3platform.gasestimator.model;

import lombok.Data;

@Data
public class GasEstimateRequest {

    private String chainId;
    private String txType;
    private Long gasLimit;
    private String speed;
    private String toAddress;
    private String data;
}
