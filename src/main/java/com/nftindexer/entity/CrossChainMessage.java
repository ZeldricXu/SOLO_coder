package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cross_chain_message")
public class CrossChainMessage extends BaseEntity {

    private String messageId;
    private String bridgeId;
    private String sourceChain;
    private String targetChain;
    private String messageType;
    private String payloadHash;
    private String payload;
    private String signature;
    private String signers;
    private Integer signatureCount;
    private Integer requiredSignatures;
    private String status;
    private LocalDateTime submittedAt;
    private LocalDateTime verifiedAt;
    private LocalDateTime executedAt;
    private Map<String, Object> proofData;
    private String errorDetail;
}
