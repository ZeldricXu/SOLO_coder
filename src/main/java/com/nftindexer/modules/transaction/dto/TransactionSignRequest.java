package com.nftindexer.modules.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TransactionSignRequest {

    @NotBlank(message = "交易ID不能为空")
    private String txId;

    @NotBlank(message = "签名者地址不能为空")
    private String signerAddress;

    private String privateKey;

    private String signingKeyId;

    private String signatureType;
}
