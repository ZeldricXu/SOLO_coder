package com.chainetl.modules.gas.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GasEstimateRequest {

    @NotBlank(message = "chainId is required")
    private String chainId;

    @NotBlank(message = "transactionType is required")
    private String transactionType;

    private String fromAddress;

    private String toAddress;

    private BigInteger value;

    private String data;
}
