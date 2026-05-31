package com.web3platform.chaininteraction.controller;

import com.web3platform.chaininteraction.model.SubmitResult;
import com.web3platform.chaininteraction.model.UnifiedBlock;
import com.web3platform.chaininteraction.model.UnifiedTransaction;
import com.web3platform.chaininteraction.observability.ChainInteractionMetrics;
import com.web3platform.chaininteraction.observability.RpcCallTracer;
import com.web3platform.chaininteraction.observability.RpcSpan;
import com.web3platform.chaininteraction.service.ChainClient;
import com.web3platform.chaininteraction.service.ChainClientFactory;
import com.web3platform.chaininteraction.service.TransactionService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chain")
@RequiredArgsConstructor
public class ChainController {

    private final ChainClientFactory chainClientFactory;
    private final TransactionService transactionService;
    private final ChainInteractionMetrics chainInteractionMetrics;
    private final RpcCallTracer rpcCallTracer;
    private final MeterRegistry meterRegistry;

    @GetMapping("/{chainId}/block/{blockNumber}")
    public ResponseEntity<UnifiedBlock> getBlockByNumber(
            @PathVariable String chainId,
            @PathVariable long blockNumber) {
        ChainClient client = chainClientFactory.getClient(chainId);
        UnifiedBlock block = client.getBlockByNumber(chainId, blockNumber);
        return ResponseEntity.ok(block);
    }

    @GetMapping("/{chainId}/block/hash/{blockHash}")
    public ResponseEntity<UnifiedBlock> getBlockByHash(
            @PathVariable String chainId,
            @PathVariable String blockHash) {
        ChainClient client = chainClientFactory.getClient(chainId);
        UnifiedBlock block = client.getBlockByHash(chainId, blockHash);
        return ResponseEntity.ok(block);
    }

    @GetMapping("/{chainId}/tx/{txHash}")
    public ResponseEntity<UnifiedTransaction> getTransaction(
            @PathVariable String chainId,
            @PathVariable String txHash) {
        UnifiedTransaction tx = transactionService.getTransactionStatus(chainId, txHash);
        return ResponseEntity.ok(tx);
    }

    @PostMapping("/{chainId}/tx/submit")
    public ResponseEntity<SubmitResult> submitTransaction(
            @PathVariable String chainId,
            @RequestBody String signedTxHex) {
        SubmitResult result = transactionService.submitTransaction(chainId, signedTxHex);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{chainId}/latest-block")
    public ResponseEntity<Long> getLatestBlockNumber(@PathVariable String chainId) {
        ChainClient client = chainClientFactory.getClient(chainId);
        long latestBlock = client.getLatestBlockNumber(chainId);
        return ResponseEntity.ok(latestBlock);
    }

    @GetMapping("/metrics/summary")
    public ResponseEntity<List<Map<String, Object>>> getMetricsSummary() {
        List<Map<String, Object>> summary = new ArrayList<>();
        for (String chainId : chainClientFactory.getRegisteredChainIds()) {
            Map<String, Object> chainMetrics = new LinkedHashMap<>();
            chainMetrics.put("chainId", chainId);

            double requestCount = getCounterValue("chain_rpc_requests_total", chainId);
            double errorCount = getCounterValue("chain_rpc_errors_total", chainId);
            double errorRate = requestCount > 0 ? errorCount / requestCount : 0.0;

            chainMetrics.put("totalRequests", (long) requestCount);
            chainMetrics.put("totalErrors", (long) errorCount);
            chainMetrics.put("errorRate", errorRate);
            chainMetrics.put("avgLatencyMs", getTimerAvgLatency("chain_rpc_duration", chainId));

            try {
                ChainClient client = chainClientFactory.getClient(chainId);
                long latestBlock = client.getLatestBlockNumber(chainId);
                chainMetrics.put("latestBlock", latestBlock);
            } catch (Exception e) {
                chainMetrics.put("latestBlock", -1);
            }

            summary.add(chainMetrics);
        }
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/tracing/active")
    public ResponseEntity<List<RpcSpan>> getActiveTracings() {
        List<RpcSpan> activeSpans = rpcCallTracer.getActiveSpans();
        return ResponseEntity.ok(activeSpans);
    }

    @GetMapping("/{chainId}/health")
    public ResponseEntity<Map<String, Object>> getChainHealth(@PathVariable String chainId) {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("chainId", chainId);
        try {
            ChainClient client = chainClientFactory.getClient(chainId);
            long startTime = System.currentTimeMillis();
            long latestBlock = client.getLatestBlockNumber(chainId);
            long responseTime = System.currentTimeMillis() - startTime;
            health.put("connected", true);
            health.put("latestBlock", latestBlock);
            health.put("responseTime", responseTime);
        } catch (Exception e) {
            health.put("connected", false);
            health.put("latestBlock", -1);
            health.put("responseTime", -1);
            health.put("error", e.getMessage());
        }
        return ResponseEntity.ok(health);
    }

    private double getCounterValue(String metricName, String chainId) {
        return meterRegistry.find(metricName).tag("chainId", chainId).counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
    }

    private double getTimerAvgLatency(String metricName, String chainId) {
        return meterRegistry.find(metricName).tag("chainId", chainId).timers().stream()
                .mapToDouble(t -> t.mean(java.util.concurrent.TimeUnit.MILLISECONDS))
                .average()
                .orElse(0.0);
    }
}
