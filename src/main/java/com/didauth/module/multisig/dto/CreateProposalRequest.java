package com.didauth.module.multisig.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.math.BigInteger;

@Data
public class CreateProposalRequest implements Serializable {

    @NotBlank(message = "walletId不能为空")
    private String walletId;

    private String toAddress;

    private String value;

    private String data;

    private String nonce;

    private String description;
}
