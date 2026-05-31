package com.nftindexer.modules.indexer.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class TransactionIndexRequest {

    @NotBlank(message = "交易哈希不能为空")
    private String txHash;

    private Integer transactionIndex;

    private String fromAddress;

    private String toAddress;

    private String contractAddress;

    private BigInteger value;

    private BigInteger gasPrice;

    private BigInteger gasLimit;

    private BigInteger gasUsed;

    private BigInteger nonce;

    private String methodName;

    private String methodSignature;

    private Map<String, Object> inputData;

    private String rawInput;

    private String status;

    private String errorReason;

    private LocalDateTime blockTime;

    private Map<String, Object> metadata;
}
