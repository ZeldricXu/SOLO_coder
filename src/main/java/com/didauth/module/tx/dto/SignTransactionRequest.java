package com.didauth.module.tx.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class SignTransactionRequest implements Serializable {

    @NotBlank(message = "txId不能为空")
    private String txId;

    @NotBlank(message = "privateKey不能为空")
    private String privateKey;

    private String signType = "ECDSA";
}
