package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("transaction_record")
public class TransactionRecord extends BaseEntity {

    private String txId;
    private String chainId;
    private String fromAddress;
    private String toAddress;
    private String contractAddress;
    private String methodName;
    private BigInteger value;
    private BigInteger gasLimit;
    private BigInteger gasPrice;
    private BigInteger priorityFee;
    private BigInteger maxFeePerGas;
    private BigInteger nonce;
    private String data;
    private String signedTx;
    private String txHash;
    private String status;
    private Integer confirmations;
    private BigInteger gasUsed;
    private BigInteger actualGasPrice;
    private BigInteger transactionFee;
    private String errorDetail;
    private String rawResponse;
    private LocalDateTime submittedAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime failedAt;
    private Map<String, Object> metadata;
}
