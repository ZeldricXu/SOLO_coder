package com.nftindexer.modules.bridge.dto;

import lombok.Data;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class BridgeStatusResponse {

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
    private Integer sourceConfirmations;
    private Integer requiredConfirmations;
    private LocalDateTime lockedAt;
    private LocalDateTime mintedAt;
    private LocalDateTime completedAt;
    private String errorDetail;
    private Map<String, Object> metadata;
}
