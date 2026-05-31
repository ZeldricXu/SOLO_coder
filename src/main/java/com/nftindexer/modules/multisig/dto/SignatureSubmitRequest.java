package com.nftindexer.modules.multisig.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SignatureSubmitRequest {

    @NotBlank(message = "提案ID不能为空")
    private String proposalId;

    @NotBlank(message = "签名者地址不能为空")
    private String signerAddress;

    @NotBlank(message = "签名数据不能为空")
    private String signature;

    private String signatureType;

    private String signedBy;
}
