package com.chainetl.modules.indexer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexedBlockResponse {

    private String blockId;
    private String chainId;
    private Long blockNumber;
    private String blockHash;
    private String parentHash;
    private Instant timestamp;
    private Integer transactionCount;
    private Instant indexedAt;
    private List<IndexedTransactionResponse> transactions;
}
