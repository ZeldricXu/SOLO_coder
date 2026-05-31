package com.web3platform.crosschainbridge.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BridgeStatus {

    private String bridgeId;
    private String sourceChain;
    private String targetChain;
    private String status;
    private String lockTxHash;
    private String mintTxHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
