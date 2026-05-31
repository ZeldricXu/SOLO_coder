package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("multi_sig_proposal")
public class MultiSigProposal extends BaseEntity {

    private String proposalId;
    private String walletId;
    private String title;
    private String description;
    private String transactionData;
    private String toAddress;
    private BigInteger value;
    private BigInteger nonce;
    private String chainId;
    private Integer requiredSignatures;
    private Integer currentSignatures;
    private String status;
    private String createdBy;
    private String executedBy;
    private String txHash;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime executedAt;
    private String rejectionReason;
}
