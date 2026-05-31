package com.web3platform.txbuilder.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GasOptimizationParams {

    private String speed;
    private BigInteger maxPriorityFee;
    private BigInteger maxFeePerGas;
    private long deadline;

    public enum Speed {
        SLOW, NORMAL, FAST, URGENT
    }
}
