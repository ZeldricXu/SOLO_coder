package com.solocoder.platform.transaction.adapter.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TransactionSignRequestDto {

    @NotBlank(message = "交易ID不能为空")
    private String txId;

    @NotBlank(message = "签名人地址不能为空")
    private String signer;

    @NotBlank(message = "私钥不能为空")
    private String privateKey;
}
