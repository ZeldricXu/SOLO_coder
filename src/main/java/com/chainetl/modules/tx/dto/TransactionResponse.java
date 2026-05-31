package com.chainetl.modules.tx.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {

    private String txId;
    private String chainId;
    private String fromAddress;
    private String toAddress;
    private BigInteger value;
    private Long gasLimit;
    private Long gasPrice;
    private Long nonce;
    private String data;
    private String signedTx;
    private String multisigWalletId;
    private String status;
    private String txHash;
    private Instant submittedAt;
    private Instant createdAt;
    private GasOptimizationInfo gasOptimization;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GasOptimizationInfo {
        private String strategy;
        private Long estimatedSavings;
        private Double savingsPercentage;
    }
}
