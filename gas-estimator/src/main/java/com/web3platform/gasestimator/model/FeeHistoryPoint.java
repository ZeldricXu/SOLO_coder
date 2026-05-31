package com.web3platform.gasestimator.model;

import lombok.Data;

import java.math.BigInteger;
import java.util.List;

@Data
public class FeeHistoryPoint {

    private Long blockNumber;
    private BigInteger baseFee;
    private List<BigInteger> reward;
    private double gasUsedRatio;
}
