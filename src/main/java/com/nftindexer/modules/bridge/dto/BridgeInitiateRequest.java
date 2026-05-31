package com.nftindexer.modules.bridge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigInteger;
import java.util.Map;

@Data
public class BridgeInitiateRequest {

    @NotBlank(message = "源链不能为空")
    private String sourceChain;

    @NotBlank(message = "目标链不能为空")
    private String targetChain;

    @NotBlank(message = "源代币地址不能为空")
    private String sourceToken;

    private String targetToken;

    private BigInteger sourceTokenId;

    @NotNull(message = "数量不能为空")
    private BigInteger amount;

    @NotBlank(message = "发送者地址不能为空")
    private String sender;

    @NotBlank(message = "接收者地址不能为空")
    private String recipient;

    private String sourceTxHash;

    private Map<String, Object> metadata;
}
