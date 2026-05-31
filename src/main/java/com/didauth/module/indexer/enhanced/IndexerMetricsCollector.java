package com.didauth.module.indexer.enhanced;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Getter
public class IndexerMetricsCollector {

    private final MeterRegistry meterRegistry;

    private final Map<String, Counter> blockCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> transactionCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> errorCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> skippedCounters = new ConcurrentHashMap<>();

    private final Map<String, Timer> blockIndexingTimers = new ConcurrentHashMap<>();
    private final Map<String, Timer> transactionIndexingTimers = new ConcurrentHashMap<>();
    private final Map<String, Timer> blockParseTimers = new ConcurrentHashMap<>();
    private final Map<String, Timer> dbInsertTimers = new ConcurrentHashMap<>();

    private final Map<String, AtomicLong> lastBlockNumbers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastIndexingDurations = new ConcurrentHashMap<>();

    public IndexerMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Counter getBlockCounter(String chainType) {
        return blockCounters.computeIfAbsent(chainType, k ->
                Counter.builder("indexer.blocks.indexed.total")
                        .description("Total number of blocks indexed")
                        .tag("chain", chainType)
                        .register(meterRegistry));
    }

    public Counter getTransactionCounter(String chainType) {
        return transactionCounters.computeIfAbsent(chainType, k ->
                Counter.builder("indexer.transactions.indexed.total")
                        .description("Total number of transactions indexed")
                        .tag("chain", chainType)
                        .register(meterRegistry));
    }

    public Counter getErrorCounter(String chainType) {
        return errorCounters.computeIfAbsent(chainType, k ->
                Counter.builder("indexer.errors.total")
                        .description("Total number of indexing errors")
                        .tag("chain", chainType)
                        .register(meterRegistry));
    }

    public Counter getSkippedCounter(String chainType) {
        return skippedCounters.computeIfAbsent(chainType, k ->
                Counter.builder("indexer.blocks.skipped.total")
                        .description("Total number of blocks skipped (already indexed)")
                        .tag("chain", chainType)
                        .register(meterRegistry));
    }

    public Timer getBlockIndexingTimer(String chainType) {
        return blockIndexingTimers.computeIfAbsent(chainType, k ->
                Timer.builder("indexer.block.indexing.duration")
                        .description("Time taken to index a complete block")
                        .tag("chain", chainType)
                        .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                        .register(meterRegistry));
    }

    public Timer getTransactionIndexingTimer(String chainType) {
        return transactionIndexingTimers.computeIfAbsent(chainType, k ->
                Timer.builder("indexer.transaction.indexing.duration")
                        .description("Time taken to index a single transaction")
                        .tag("chain", chainType)
                        .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                        .register(meterRegistry));
    }

    public Timer getBlockParseTimer(String chainType) {
        return blockParseTimers.computeIfAbsent(chainType, k ->
                Timer.builder("indexer.block.parse.duration")
                        .description("Time taken to parse block data")
                        .tag("chain", chainType)
                        .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                        .register(meterRegistry));
    }

    public Timer getDbInsertTimer(String chainType) {
        return dbInsertTimers.computeIfAbsent(chainType, k ->
                Timer.builder("indexer.db.insert.duration")
                        .description("Time taken to insert block/transactions into database")
                        .tag("chain", chainType)
                        .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                        .register(meterRegistry));
    }

    public void registerBlockGauge(String chainType) {
        AtomicLong lastBlock = lastBlockNumbers.computeIfAbsent(chainType, k -> new AtomicLong(0));
        Gauge.builder("indexer.block.latest.number")
                .description("Latest block number indexed")
                .tag("chain", chainType)
                .register(meterRegistry, lastBlock, AtomicLong::get);

        AtomicLong lastDuration = lastIndexingDurations.computeIfAbsent(chainType, k -> new AtomicLong(0));
        Gauge.builder("indexer.block.last.duration.ms")
                .description("Duration of last block indexing in milliseconds")
                .tag("chain", chainType)
                .register(meterRegistry, lastDuration, AtomicLong::get);
    }

    public void setLastBlockNumber(String chainType, long blockNumber) {
        lastBlockNumbers.computeIfAbsent(chainType, k -> new AtomicLong(0)).set(blockNumber);
    }

    public void setLastIndexingDuration(String chainType, long durationMs) {
        lastIndexingDurations.computeIfAbsent(chainType, k -> new AtomicLong(0)).set(durationMs);
    }
}
