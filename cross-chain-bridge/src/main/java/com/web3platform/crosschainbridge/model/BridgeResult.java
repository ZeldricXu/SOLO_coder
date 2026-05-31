package com.web3platform.crosschainbridge.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BridgeResult {

    private boolean success;
    private String lockTxHash;
    private String mintTxHash;
    private String error;
}
