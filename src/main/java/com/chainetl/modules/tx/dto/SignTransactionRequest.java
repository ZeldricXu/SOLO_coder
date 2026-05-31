package com.chainetl.modules.tx.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignTransactionRequest {

    @NotBlank(message = "txId is required")
    private String txId;

    @NotBlank(message = "signerAddress is required")
    private String signerAddress;

    private String signature;

    private String signingStrategy;
}
