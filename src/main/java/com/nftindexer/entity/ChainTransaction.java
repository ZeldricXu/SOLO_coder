package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chain_transaction")
public class ChainTransaction extends BaseEntity {

    private String txIndexId;
    private String chainId;
    private String txHash;
    private Integer blockNumber;
    private String blockHash;
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
    private LocalDateTime indexedAt;
    private Map<String, Object> metadata;
}
