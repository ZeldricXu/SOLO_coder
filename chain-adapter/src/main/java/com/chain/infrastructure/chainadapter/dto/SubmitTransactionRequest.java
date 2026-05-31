package com.chain.infrastructure.chainadapter.dto;

import lombok.Data;

@Data
public class SubmitTransactionRequest {

    private String chainType;

    private String signedTransaction;

    private String txHash;
}
