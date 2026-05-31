package com.contraudit.multisig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CreateProposalRequest {

    @NotBlank(message = "wallet id cannot be blank")
    private String walletId;

    @NotBlank(message = "proposal type cannot be blank")
    private String proposalType;

    @NotBlank(message = "title cannot be blank")
    private String title;

    private String description;

    @NotBlank(message = "to address cannot be blank")
    private String toAddress;

    private BigDecimal value;

    private String data;

    private Long nonce;

    private Long gasLimit;

    private BigDecimal gasPrice;

    @NotBlank(message = "creator address cannot be blank")
    private String creatorAddress;

    private LocalDateTime expireAt;
}
