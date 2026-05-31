package com.web3platform.chaininteraction.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedBlock {

    private String chainId;
    private long blockNumber;
    private String blockHash;
    private String parentHash;
    private long timestamp;
    private List<UnifiedTransaction> transactions;
}
