package com.chain.infrastructure.txbuilder.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Map;

@Data
public class TransactionRequest {

    private String chainType;

    private Integer chainId;

    private String fromAddress;

    private String toAddress;

    private BigDecimal amount;

    private Long gasLimit;

    private BigDecimal gasPrice;

    private Long nonce;

    private String txData;

    private String multisigWalletId;

    private Map<String, Object> options;
}
