package com.chainetl.modules.chain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitTransactionRequest {

    @NotBlank(message = "chainId is required")
    private String chainId;

    @NotBlank(message = "signedTx is required")
    private String signedTx;
}
