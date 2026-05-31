package com.chainetl.modules.multisig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateProposalRequest {

    @NotBlank(message = "walletId is required")
    private String walletId;

    @NotBlank(message = "chainId is required")
    private String chainId;

    @NotBlank(message = "transactionData is required")
    private String transactionData;

    @NotNull(message = "requiredSignatures is required")
    @Positive(message = "requiredSignatures must be positive")
    private Integer requiredSignatures;

    @NotBlank(message = "proposer is required")
    private String proposer;

    private Long expireSeconds;
}
