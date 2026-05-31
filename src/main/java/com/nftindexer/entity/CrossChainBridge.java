package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cross_chain_bridge")
public class CrossChainBridge extends BaseEntity {

    private String bridgeId;
    private String sourceChain;
    private String targetChain;
    private String sourceToken;
    private String targetToken;
    private BigInteger sourceTokenId;
    private BigInteger targetTokenId;
    private String sourceTxHash;
    private String targetTxHash;
    private String sender;
    private String recipient;
    private BigInteger amount;
    private String status;
    private String messageProof;
    private String merkleProof;
    private Map<String, Object> metadata;
    private Integer sourceConfirmations;
    private Integer requiredConfirmations;
    private LocalDateTime lockedAt;
    private LocalDateTime mintedAt;
    private LocalDateTime completedAt;
    private String errorDetail;
}
