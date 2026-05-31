package com.chain.infrastructure.crosschainbridge.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class CrossChainTransferRequest {

    private String sourceChain;

    private String targetChain;

    private String sourceAddress;

    private String targetAddress;

    private String tokenAddress;

    private BigDecimal amount;

    private BigDecimal fee;

    private String sourceTxHash;
}
