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
public class SubmitTransactionRequest {

    @NotBlank(message = "txId is required")
    private String txId;

    private String signedTx;

    private Integer maxRetries;
}
