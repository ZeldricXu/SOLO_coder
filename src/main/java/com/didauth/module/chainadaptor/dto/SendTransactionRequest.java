package com.didauth.module.chainadaptor.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class SendTransactionRequest implements Serializable {

    @NotBlank(message = "chainType不能为空")
    private String chainType;

    @NotBlank(message = "signedTx不能为空")
    private String signedTx;

    private String walletId;
}
