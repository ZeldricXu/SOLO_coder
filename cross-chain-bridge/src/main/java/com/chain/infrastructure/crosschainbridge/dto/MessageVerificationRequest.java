package com.chain.infrastructure.crosschainbridge.dto;

import lombok.Data;

@Data
public class MessageVerificationRequest {

    private String transferId;

    private String sourceChain;

    private String targetChain;

    private String messageProof;

    private String merkleProof;

    private String blockHeader;
}
