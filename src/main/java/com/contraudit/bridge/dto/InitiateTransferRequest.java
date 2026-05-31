package com.contraudit.bridge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class InitiateTransferRequest {

    @NotNull(message = "from chain id cannot be null")
    private Long fromChainId;

    @NotNull(message = "to chain id cannot be null")
    private Long toChainId;

    @NotBlank(message = "from address cannot be blank")
    private String fromAddress;

    @NotBlank(message = "to address cannot be blank")
    private String toAddress;

    @NotBlank(message = "token address cannot be blank")
    private String tokenAddress;

    @NotBlank(message = "token symbol cannot be blank")
    private String tokenSymbol;

    @NotNull(message = "amount cannot be null")
    private BigDecimal amount;

    private BigDecimal fee;
}
