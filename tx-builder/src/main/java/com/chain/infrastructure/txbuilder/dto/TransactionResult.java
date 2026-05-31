package com.chain.infrastructure.txbuilder.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class TransactionResult {

    private String txId;

    private String chainType;

    private String fromAddress;

    private String toAddress;

    private BigDecimal amount;

    private Long gasLimit;

    private BigDecimal gasPrice;

    private BigDecimal estimatedFee;

    private String signedTx;

    private String txHash;

    private String status;

    private String multisigProposalId;
}
