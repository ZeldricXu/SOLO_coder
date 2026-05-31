package com.web3platform.txbuilder.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionBuildResult {

    private String signedTxHex;
    private String txHash;
    private String rawTx;
    private long gasUsed;
}
