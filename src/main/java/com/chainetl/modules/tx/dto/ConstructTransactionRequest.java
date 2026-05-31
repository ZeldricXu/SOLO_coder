package com.chainetl.modules.tx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConstructTransactionRequest {

    @NotBlank(message = "chainId is required")
    private String chainId;

    @NotBlank(message = "fromAddress is required")
    private String fromAddress;

    private String toAddress;

    @NotNull(message = "value is required")
    private BigInteger value;

    private String data;

    private Long gasLimit;

    private Long gasPrice;

    private Long nonce;

    private String multisigWalletId;

    private String gasOptimizationStrategy;

    private Map<String, Object> options;
}
