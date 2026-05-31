package com.chain.infrastructure.chainindexer.monitor;

import lombok.Data;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
@Component
@Endpoint(id = "indexerStatus")
public class IndexerStatusEndpoint {

    private final IndexerMetrics metrics;
    private LocalDateTime lastIndexedTime;
    private Long lastIndexedBlockNumber;
    private String lastIndexedChain;
    private String status = "IDLE";

    @ReadOperation
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", this.status);
        status.put("indexedBlocksTotal", metrics.getIndexedBlocksCount());
        status.put("indexedTransactionsTotal", metrics.getIndexedTransactionsCount());
        status.put("lastIndexedTime", lastIndexedTime != null ? lastIndexedTime.toString() : null);
        status.put("lastIndexedBlockNumber", lastIndexedBlockNumber);
        status.put("lastIndexedChain", lastIndexedChain);
        status.put("timestamp", LocalDateTime.now().toString());
        return status;
    }

    public void updateLastIndexed(String chain, Long blockNumber) {
        this.lastIndexedChain = chain;
        this.lastIndexedBlockNumber = blockNumber;
        this.lastIndexedTime = LocalDateTime.now();
        this.status = "RUNNING";
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
