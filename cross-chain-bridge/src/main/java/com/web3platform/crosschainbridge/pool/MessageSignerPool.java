package com.web3platform.crosschainbridge.pool;

import com.web3platform.crosschainbridge.config.ResourcePoolConfig;
import com.web3platform.crosschainbridge.model.PoolStatistics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class MessageSignerPool {

    private final ResourcePoolConfig config;
    private final Map<String, BlockingQueue<Credentials>> signerQueues = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> totalCreatedCounters = new ConcurrentHashMap<>();
    private final Map<String, Integer> activeCounts = new ConcurrentHashMap<>();

    public MessageSignerPool(ResourcePoolConfig config) {
        this.config = config;
    }

    public void initialize() {
        log.info("MessageSignerPool initialized with pool size: {}", config.getMessageSignerPoolSize());
    }

    public Credentials borrowSigner(String keyIdentifier) {
        BlockingQueue<Credentials> queue = signerQueues.computeIfAbsent(
                keyIdentifier,
                k -> new LinkedBlockingQueue<>(config.getMessageSignerPoolSize()));

        totalCreatedCounters.computeIfAbsent(keyIdentifier, k -> new AtomicLong(0));

        try {
            Credentials signer = queue.poll(5000, TimeUnit.MILLISECONDS);
            if (signer == null) {
                signer = Credentials.create(keyIdentifier);
                totalCreatedCounters.get(keyIdentifier).incrementAndGet();
                log.debug("Created new Credentials for key: {}", keyIdentifier);
            }
            activeCounts.merge(keyIdentifier, 1, Integer::sum);
            log.debug("Borrowed signer for key: {}, active: {}", keyIdentifier, activeCounts.get(keyIdentifier));
            return signer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for signer", e);
        }
    }

    public void returnSigner(String keyIdentifier, Credentials signer) {
        if (keyIdentifier == null || signer == null) {
            return;
        }
        BlockingQueue<Credentials> queue = signerQueues.get(keyIdentifier);
        if (queue == null) {
            log.warn("No queue found for key when returning signer: {}", keyIdentifier);
            return;
        }
        if (queue.offer(signer)) {
            activeCounts.merge(keyIdentifier, -1, Integer::sum);
            log.debug("Returned signer for key: {}, active: {}", keyIdentifier, activeCounts.get(keyIdentifier));
        } else {
            log.warn("Signer queue full for key: {}, discarding signer", keyIdentifier);
        }
    }

    public PoolStatistics getPoolStats(String keyIdentifier) {
        BlockingQueue<Credentials> queue = signerQueues.get(keyIdentifier);
        if (queue == null) {
            return null;
        }
        return PoolStatistics.builder()
                .poolName("Signer-" + keyIdentifier)
                .activeCount(activeCounts.getOrDefault(keyIdentifier, 0))
                .idleCount(queue.size())
                .waitCount(0)
                .totalCreated(totalCreatedCounters.getOrDefault(keyIdentifier, new AtomicLong(0)).get())
                .build();
    }

    public PoolStatistics getPoolStats() {
        int totalActive = 0;
        int totalIdle = 0;
        long totalCreated = 0;

        for (Map.Entry<String, BlockingQueue<Credentials>> entry : signerQueues.entrySet()) {
            totalActive += activeCounts.getOrDefault(entry.getKey(), 0);
            totalIdle += entry.getValue().size();
            totalCreated += totalCreatedCounters.getOrDefault(entry.getKey(), new AtomicLong(0)).get();
        }

        return PoolStatistics.builder()
                .poolName("Signer-ALL")
                .activeCount(totalActive)
                .idleCount(totalIdle)
                .waitCount(0)
                .totalCreated(totalCreated)
                .build();
    }

    public void shutdown() {
        for (Map.Entry<String, BlockingQueue<Credentials>> entry : signerQueues.entrySet()) {
            entry.getValue().clear();
        }
        signerQueues.clear();
        activeCounts.clear();
        log.info("MessageSignerPool shutdown completed");
    }

    public void evictIdleSigners() {
        log.debug("Signer pool eviction - total keys: {}", signerQueues.size());
    }
}
