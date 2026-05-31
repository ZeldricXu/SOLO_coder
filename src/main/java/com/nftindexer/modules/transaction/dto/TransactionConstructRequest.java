package com.nftindexer.modules.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigInteger;
import java.util.Map;

@Data
public class TransactionConstructRequest {

    @NotBlank(message = "链ID不能为空")
    private String chainId;

    @NotBlank(message = "发送地址不能为空")
    private String fromAddress;

    @NotBlank(message = "接收地址不能为空")
    private String toAddress;

    private BigInteger value;

    private BigInteger gasLimit;

    private BigInteger gasPrice;

    private BigInteger priorityFee;

    private BigInteger maxFeePerGas;

    private BigInteger nonce;

    private String contractAddress;

    private String methodName;

    private Map<String, Object> methodParams;

    private String data;

    private String signerAddress;

    private String signatureType;

    private Boolean optimizeGas;

    private Map<String, Object> metadata;
}
