package com.chainetl.modules.indexer.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class IndexerMetricsService {

    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, Counter> blockIndexedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> blockFailedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> txIndexedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> blockIndexTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastIndexedBlockNumbers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastIndexedTimestamps = new ConcurrentHashMap<>();

    private DistributionSummary indexSizeDistribution;
    private Counter totalBlocksIndexed;
    private Counter totalTransactionsIndexed;
    private Counter totalFailedBlocks;
    private Timer globalIndexTimer;

    private static final String METRIC_PREFIX = "chainetl.indexer";

    @PostConstruct
    public void initMetrics() {
        totalBlocksIndexed = Counter.builder(METRIC_PREFIX + ".blocks.indexed.total")
                .description("Total number of blocks indexed across all chains")
                .register(meterRegistry);

        totalTransactionsIndexed = Counter.builder(METRIC_PREFIX + ".transactions.indexed.total")
                .description("Total number of transactions indexed across all chains")
                .register(meterRegistry);

        totalFailedBlocks = Counter.builder(METRIC_PREFIX + ".blocks.failed.total")
                .description("Total number of failed block indexing attempts")
                .register(meterRegistry);

        globalIndexTimer = Timer.builder(METRIC_PREFIX + ".block.index.time")
                .description("Time taken to index a block")
                .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);

        indexSizeDistribution = DistributionSummary.builder(METRIC_PREFIX + ".block.size")
                .description("Size distribution of indexed blocks")
                .baseUnit("bytes")
                .publishPercentiles(0.5, 0.75, 0.9, 0.95)
                .register(meterRegistry);

        log.info("Indexer metrics initialized");
    }

    public void recordBlockIndexed(String chainId, Long blockNumber, int txCount, long indexTimeMs) {
        String key = chainId;

        blockIndexedCounters.computeIfAbsent(key, k ->
                Counter.builder(METRIC_PREFIX + ".blocks.indexed")
                        .description("Number of blocks indexed for chain")
                        .tag("chainId", chainId)
                        .register(meterRegistry)
        ).increment();

        if (txCount > 0) {
            txIndexedCounters.computeIfAbsent(key, k ->
                    Counter.builder(METRIC_PREFIX + ".transactions.indexed")
                            .description("Number of transactions indexed for chain")
                            .tag("chainId", chainId)
                            .register(meterRegistry)
            ).increment(txCount);
        }

        blockIndexTimers.computeIfAbsent(key, k ->
                Timer.builder(METRIC_PREFIX + ".block.index.time")
                        .description("Time taken to index a block for chain")
                        .tag("chainId", chainId)
                        .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                        .publishPercentileHistogram()
                        .register(meterRegistry)
        ).record(java.time.Duration.ofMillis(indexTimeMs));

        totalBlocksIndexed.increment();
        if (txCount > 0) {
            totalTransactionsIndexed.increment(txCount);
        }
        globalIndexTimer.record(java.time.Duration.ofMillis(indexTimeMs));

        lastIndexedBlockNumbers.computeIfAbsent(chainId, k -> new AtomicLong(0))
                .set(blockNumber);
        lastIndexedTimestamps.computeIfAbsent(chainId, k -> new AtomicLong(0))
                .set(System.currentTimeMillis());

        log.debug("Recorded block indexed: chain={}, block={}, txCount={}, timeMs={}",
                chainId, blockNumber, txCount, indexTimeMs);
    }

    public void recordBlockFailed(String chainId, Long blockNumber) {
        String key = chainId;

        blockFailedCounters.computeIfAbsent(key, k ->
                Counter.builder(METRIC_PREFIX + ".blocks.failed")
                        .description("Number of failed block indexing for chain")
                        .tag("chainId", chainId)
                        .register(meterRegistry)
        ).increment();

        totalFailedBlocks.increment();

        log.warn("Recorded block failed: chain={}, block={}", chainId, blockNumber);
    }

    public void recordIndexSize(long sizeInBytes) {
        indexSizeDistribution.record(sizeInBytes);
    }

    public long getTotalBlocksIndexed() {
        return (long) totalBlocksIndexed.count();
    }

    public long getTotalTransactionsIndexed() {
        return (long) totalTransactionsIndexed.count();
    }

    public long getTotalFailedBlocks() {
        return (long) totalFailedBlocks.count();
    }

    public long getChainBlockCount(String chainId) {
        Counter counter = blockIndexedCounters.get(chainId);
        return counter != null ? (long) counter.count() : 0;
    }

    public long getLastIndexedBlockNumber(String chainId) {
        AtomicLong num = lastIndexedBlockNumbers.get(chainId);
        return num != null ? num.get() : 0;
    }

    public long getLastIndexedTimestamp(String chainId) {
        AtomicLong ts = lastIndexedTimestamps.get(chainId);
        return ts != null ? ts.get() : 0;
    }

    public double getAverageIndexTimeMs(String chainId) {
        Timer timer = blockIndexTimers.get(chainId);
        if (timer == null) {
            return 0.0;
        }
        return timer.takeSnapshot().mean(java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public double getP95IndexTimeMs(String chainId) {
        Timer timer = blockIndexTimers.get(chainId);
        if (timer == null) {
            return 0.0;
        }
        return timer.takeSnapshot()
                .percentileValues()
                .stream()
                .filter(v -> v.getPercentile() == 0.95)
                .findFirst()
                .map(v -> v.getValue(java.util.concurrent.TimeUnit.MILLISECONDS))
                .orElse(0.0);
    }

    public double getP99IndexTimeMs(String chainId) {
        Timer timer = blockIndexTimers.get(chainId);
        if (timer == null) {
            return 0.0;
        }
        return timer.takeSnapshot()
                .percentileValues()
                .stream()
                .filter(v -> v.getPercentile() == 0.99)
                .findFirst()
                .map(v -> v.getValue(java.util.concurrent.TimeUnit.MILLISECONDS))
                .orElse(0.0);
    }

    public double getGlobalAverageIndexTimeMs() {
        return globalIndexTimer.takeSnapshot().mean(java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public double getGlobalP95IndexTimeMs() {
        return globalIndexTimer.takeSnapshot()
                .percentileValues()
                .stream()
                .filter(v -> v.getPercentile() == 0.95)
                .findFirst()
                .map(v -> v.getValue(java.util.concurrent.TimeUnit.MILLISECONDS))
                .orElse(0.0);
    }

    public double getGlobalP99IndexTimeMs() {
        return globalIndexTimer.takeSnapshot()
                .percentileValues()
                .stream()
                .filter(v -> v.getPercentile() == 0.99)
                .findFirst()
                .map(v -> v.getValue(java.util.concurrent.TimeUnit.MILLISECONDS))
                .orElse(0.0);
    }
}
