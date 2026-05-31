package com.nftindexer.modules.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TransactionSubmitRequest {

    @NotBlank(message = "交易ID不能为空")
    private String txId;

    private String signedTx;

    private String rpcEndpoint;
}
