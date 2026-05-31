package com.chainetl.modules.indexer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexerStatusResponse {

    private String chainId;
    private Long totalBlocksIndexed;
    private Long totalTransactionsIndexed;
    private Long totalFailedBlocks;
    private Double averageIndexTimeMs;
    private Double p95IndexTimeMs;
    private Double p99IndexTimeMs;
    private Instant lastIndexedAt;
    private Long lastIndexedBlockNumber;
    private String lastIndexedBlockHash;
    private IndexerRunStatus status;
    private Map<String, Long> blockIndexCounts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexerRunStatus {
        private Long activeRuns;
        private Long totalRuns;
        private Long successfulRuns;
        private Long failedRuns;
        private Double successRate;
    }
}
