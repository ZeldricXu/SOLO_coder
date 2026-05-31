package com.chain.infrastructure.crosschainbridge.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CrossChainTransferResult {

    private String transferId;

    private String sourceChain;

    private String targetChain;

    private String sourceAddress;

    private String targetAddress;

    private String tokenAddress;

    private BigDecimal amount;

    private BigDecimal fee;

    private String sourceTxHash;

    private String targetTxHash;

    private String status;

    private String messageProof;

    private LocalDateTime createdAt;
}
