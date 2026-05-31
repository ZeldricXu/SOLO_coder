package com.web3platform.crosschainbridge.pool;

import com.web3platform.crosschainbridge.config.ResourcePoolConfig;
import com.web3platform.crosschainbridge.model.PoolStatistics;
import com.web3platform.crosschainbridge.service.MerkleProofVerifier;
import com.web3platform.crosschainbridge.service.MessageVerifier;
import com.web3platform.crosschainbridge.service.MptProofVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class VerifierPool {

    private final ResourcePoolConfig config;
    private final LinkedBlockingQueue<MessageVerifier> mptVerifierQueue;
    private final LinkedBlockingQueue<MessageVerifier> merkleVerifierQueue;
    private final AtomicLong mptTotalCreated = new AtomicLong(0);
    private final AtomicLong merkleTotalCreated = new AtomicLong(0);
    private final AtomicInteger mptActiveCount = new AtomicInteger(0);
    private final AtomicInteger merkleActiveCount = new AtomicInteger(0);

    public VerifierPool(ResourcePoolConfig config) {
        this.config = config;
        this.mptVerifierQueue = new LinkedBlockingQueue<>(config.getVerifierPoolSize());
        this.merkleVerifierQueue = new LinkedBlockingQueue<>(config.getVerifierPoolSize());
    }

    public void initialize() {
        for (int i = 0; i < config.getVerifierPoolSize(); i++) {
            MessageVerifier mptVerifier = new MptProofVerifier();
            mptVerifierQueue.offer(mptVerifier);
            mptTotalCreated.incrementAndGet();

            MessageVerifier merkleVerifier = new MerkleProofVerifier();
            merkleVerifierQueue.offer(merkleVerifier);
            merkleTotalCreated.incrementAndGet();
        }
        log.info("Initialized VerifierPool with {} MPT and {} Merkle verifiers",
                config.getVerifierPoolSize(), config.getVerifierPoolSize());
    }

    public MessageVerifier borrowMptVerifier() {
        try {
            MessageVerifier verifier = mptVerifierQueue.poll(
                    config.getVerifierPoolMaxWaitMs(), TimeUnit.MILLISECONDS);
            if (verifier == null) {
                throw new IllegalStateException("Timeout waiting for MPT verifier from pool");
            }
            int active = mptActiveCount.incrementAndGet();
            log.debug("Borrowed MPT verifier, active: {}, idle: {}", active, mptVerifierQueue.size());
            return verifier;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for MPT verifier", e);
        }
    }

    public void returnMptVerifier(MessageVerifier verifier) {
        if (verifier == null) {
            return;
        }
        if (mptVerifierQueue.offer(verifier)) {
            int active = mptActiveCount.decrementAndGet();
            log.debug("Returned MPT verifier, active: {}, idle: {}", active, mptVerifierQueue.size());
        } else {
            log.warn("MPT verifier queue full, discarding verifier");
        }
    }

    public MessageVerifier borrowMerkleVerifier() {
        try {
            MessageVerifier verifier = merkleVerifierQueue.poll(
                    config.getVerifierPoolMaxWaitMs(), TimeUnit.MILLISECONDS);
            if (verifier == null) {
                throw new IllegalStateException("Timeout waiting for Merkle verifier from pool");
            }
            int active = merkleActiveCount.incrementAndGet();
            log.debug("Borrowed Merkle verifier, active: {}, idle: {}", active, merkleVerifierQueue.size());
            return verifier;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Merkle verifier", e);
        }
    }

    public void returnMerkleVerifier(MessageVerifier verifier) {
        if (verifier == null) {
            return;
        }
        if (merkleVerifierQueue.offer(verifier)) {
            int active = merkleActiveCount.decrementAndGet();
            log.debug("Returned Merkle verifier, active: {}, idle: {}", active, merkleVerifierQueue.size());
        } else {
            log.warn("Merkle verifier queue full, discarding verifier");
        }
    }

    public PoolStatistics getMptPoolStats() {
        return PoolStatistics.builder()
                .poolName("Verifier-MPT")
                .activeCount(mptActiveCount.get())
                .idleCount(mptVerifierQueue.size())
                .waitCount(0)
                .totalCreated(mptTotalCreated.get())
                .build();
    }

    public PoolStatistics getMerklePoolStats() {
        return PoolStatistics.builder()
                .poolName("Verifier-Merkle")
                .activeCount(merkleActiveCount.get())
                .idleCount(merkleVerifierQueue.size())
                .waitCount(0)
                .totalCreated(merkleTotalCreated.get())
                .build();
    }

    public void shutdown() {
        mptVerifierQueue.clear();
        merkleVerifierQueue.clear();
        mptActiveCount.set(0);
        merkleActiveCount.set(0);
        log.info("VerifierPool shutdown completed");
    }

    public void evictIdleVerifiers() {
        log.debug("Verifier pool eviction - MPT idle: {}, Merkle idle: {}",
                mptVerifierQueue.size(), merkleVerifierQueue.size());
    }
}
