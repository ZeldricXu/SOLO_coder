package com.chain.infrastructure.chainindexer.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class IndexerMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DistributionSummary> summaries = new ConcurrentHashMap<>();
    private final AtomicLong indexedBlocks = new AtomicLong(0);
    private final AtomicLong indexedTransactions = new AtomicLong(0);

    public IndexerMetrics(MeterRegistry registry) {
        this.registry = registry;
        registerGauges();
    }

    private void registerGauges() {
        registry.gauge("chain_indexer_blocks_indexed_total", indexedBlocks);
        registry.gauge("chain_indexer_transactions_indexed_total", indexedTransactions);
    }

    public Timer.Sample startTimer(String operation) {
        return Timer.start(registry);
    }

    public void recordTimer(String operation, Timer.Sample sample, String... tags) {
        Timer timer = timers.computeIfAbsent(operation, k ->
                Timer.builder("chain_indexer_operation_duration_seconds")
                        .description("Duration of indexer operations")
                        .tag("operation", operation)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry));
        sample.stop(timer);
    }

    public void incrementCounter(String name, String... tags) {
        Counter counter = counters.computeIfAbsent(name, k ->
                Counter.builder("chain_indexer_" + name + "_total")
                        .description("Number of " + name)
                        .register(registry));
        counter.increment();
    }

    public void recordSummary(String name, double value, String... tags) {
        DistributionSummary summary = summaries.computeIfAbsent(name, k ->
                DistributionSummary.builder("chain_indexer_" + name)
                        .description(name)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry));
        summary.record(value);
    }

    public void incrementBlocksIndexed() {
        indexedBlocks.incrementAndGet();
    }

    public void incrementTransactionsIndexed(long count) {
        indexedTransactions.addAndGet(count);
    }

    public void recordBlockIndexingTime(Duration duration) {
        recordSummary("block_indexing_time_ms", duration.toMillis());
    }

    public void recordTransactionIndexingTime(Duration duration) {
        recordSummary("transaction_indexing_time_ms", duration.toMillis());
    }

    public void recordBlockSize(long sizeBytes) {
        recordSummary("block_size_bytes", sizeBytes);
    }

    public void recordTransactionCountPerBlock(int count) {
        recordSummary("transactions_per_block", count);
    }

    public long getIndexedBlocksCount() {
        return indexedBlocks.get();
    }

    public long getIndexedTransactionsCount() {
        return indexedTransactions.get();
    }
}
