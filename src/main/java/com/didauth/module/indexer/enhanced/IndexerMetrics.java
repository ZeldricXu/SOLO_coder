package com.didauth.module.indexer.enhanced;

import lombok.Data;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicLong;

@Data
public class IndexerMetrics implements Serializable {

    private AtomicLong totalBlocksIndexed = new AtomicLong(0);
    private AtomicLong totalTransactionsIndexed = new AtomicLong(0);
    private AtomicLong totalBlocksSkipped = new AtomicLong(0);
    private AtomicLong totalErrors = new AtomicLong(0);

    private volatile long lastBlockNumber = 0;
    private volatile long lastBlockTimestamp = 0;
    private volatile long lastIndexingDurationMs = 0;
    private volatile double avgBlockIndexingTimeMs = 0;
    private volatile double avgTransactionIndexingTimeMs = 0;

    private final Object lock = new Object();

    public void recordBlockIndexed(long blockNumber, long blockTimestamp, long durationMs, int txCount) {
        totalBlocksIndexed.incrementAndGet();
        totalTransactionsIndexed.addAndGet(txCount);
        lastBlockNumber = blockNumber;
        lastBlockTimestamp = blockTimestamp;
        lastIndexingDurationMs = durationMs;

        synchronized (lock) {
            double currentAvg = avgBlockIndexingTimeMs;
            long count = totalBlocksIndexed.get();
            avgBlockIndexingTimeMs = (currentAvg * (count - 1) + durationMs) / count;

            if (txCount > 0) {
                double txTime = (double) durationMs / txCount;
                double currentTxAvg = avgTransactionIndexingTimeMs;
                long txTotal = totalTransactionsIndexed.get();
                avgTransactionIndexingTimeMs = (currentTxAvg * (txTotal - txCount) + txTime * txCount) / txTotal;
            }
        }
    }

    public void recordBlockSkipped() {
        totalBlocksSkipped.incrementAndGet();
    }

    public void recordError() {
        totalErrors.incrementAndGet();
    }

    public double getIndexingRateTps() {
        if (avgBlockIndexingTimeMs <= 0) return 0;
        return 1000.0 / avgBlockIndexingTimeMs;
    }

    public double getTransactionRateTps() {
        if (avgTransactionIndexingTimeMs <= 0) return 0;
        return 1000.0 / avgTransactionIndexingTimeMs;
    }
}
