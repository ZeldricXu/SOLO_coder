package com.web3platform.chaininteraction.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class ChainInteractionMetrics {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, AtomicLong> latestBlockNumbers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> activeConnections = new ConcurrentHashMap<>();

    public ChainInteractionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRequest(String chainId, String method) {
        Counter.builder("chain_rpc_requests_total")
                .tag("chainId", chainId)
                .tag("method", method)
                .tag("status", "total")
                .register(meterRegistry)
                .increment();
    }

    public void recordError(String chainId, String method, String errorType) {
        Counter.builder("chain_rpc_errors_total")
                .tag("chainId", chainId)
                .tag("method", method)
                .tag("errorType", errorType)
                .register(meterRegistry)
                .increment();
    }

    public void recordDuration(String chainId, String method, long durationMs) {
        Timer.builder("chain_rpc_duration")
                .tag("chainId", chainId)
                .tag("method", method)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    public void updateLatestBlock(String chainId, long blockNumber) {
        AtomicLong blockValue = latestBlockNumbers.computeIfAbsent(chainId, k -> {
            AtomicLong value = new AtomicLong(blockNumber);
            Gauge.builder("chain_latest_block", value, AtomicLong::get)
                    .tag("chainId", chainId)
                    .description("Latest block number for the chain")
                    .register(meterRegistry);
            return value;
        });
        blockValue.set(blockNumber);
    }

    public void updateActiveConnections(String chainId, long count) {
        AtomicLong connectionCount = activeConnections.computeIfAbsent(chainId, k -> {
            AtomicLong value = new AtomicLong(count);
            Gauge.builder("chain_rpc_active_connections", value, AtomicLong::get)
                    .tag("chainId", chainId)
                    .description("Active RPC connections for the chain")
                    .register(meterRegistry);
            return value;
        });
        connectionCount.set(count);
    }

    public void recordTxSubmission(String chainId, String status) {
        Counter.builder("chain_tx_submitted_total")
                .tag("chainId", chainId)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    public void recordConfirmationDuration(String chainId, long durationMs) {
        Timer.builder("chain_tx_confirmation_duration")
                .tag("chainId", chainId)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }
}
