package com.web3platform.gasestimator.model;

import lombok.Data;

import java.math.BigInteger;

@Data
public class HistoricalGasData {

    private String chainId;
    private Long blockNumber;
    private BigInteger baseFee;
    private BigInteger avgGasPrice;
    private BigInteger minGasPrice;
    private BigInteger maxGasPrice;
    private Long timestamp;
    private Integer txCount;
}
