package com.didauth.module.tx.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class BuildTransactionRequest implements Serializable {

    @NotBlank(message = "chainType不能为空")
    private String chainType;

    @NotBlank(message = "fromAddress不能为空")
    private String fromAddress;

    private String toAddress;

    private String value;

    private String data;

    private String gasPrice;

    private String gasLimit;

    private String nonce;

    private String gasPriority = "STANDARD";

    private String multisigWalletId;
}
