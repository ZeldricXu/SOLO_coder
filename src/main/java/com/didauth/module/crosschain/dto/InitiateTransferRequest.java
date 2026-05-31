package com.didauth.module.crosschain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigInteger;

@Data
public class InitiateTransferRequest implements Serializable {

    @NotBlank(message = "sourceChain不能为空")
    private String sourceChain;

    @NotBlank(message = "targetChain不能为空")
    private String targetChain;

    @NotBlank(message = "senderAddress不能为空")
    private String senderAddress;

    @NotBlank(message = "recipientAddress不能为空")
    private String recipientAddress;

    @NotNull(message = "amount不能为空")
    private String amount;

    @NotBlank(message = "assetSymbol不能为空")
    private String assetSymbol;

    private String assetAddress;

    private String bridgeId;
}
