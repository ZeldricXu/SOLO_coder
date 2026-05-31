package com.web3platform.crosschainbridge.pool;

import com.web3platform.crosschainbridge.config.ResourcePoolConfig;
import com.web3platform.crosschainbridge.model.PoolStatistics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResourcePoolManager {

    private final ResourcePoolConfig config;
    private final RpcConnectionPool rpcConnectionPool;
    private final VerifierPool verifierPool;
    private final MessageSignerPool messageSignerPool;

    @PostConstruct
    public void init() {
        log.info("Initializing all resource pools...");
        rpcConnectionPool.initialize();
        verifierPool.initialize();
        messageSignerPool.initialize();
        log.info("All resource pools initialized successfully");
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down all resource pools...");
        rpcConnectionPool.shutdown();
        verifierPool.shutdown();
        messageSignerPool.shutdown();
        log.info("All resource pools shut down successfully");
    }

    public Map<String, PoolStatistics> getPoolStatistics() {
        Map<String, PoolStatistics> stats = new LinkedHashMap<>();

        PoolStatistics rpcStats = rpcConnectionPool.getPoolStats();
        stats.put(rpcStats.getPoolName(), rpcStats);

        PoolStatistics mptStats = verifierPool.getMptPoolStats();
        stats.put(mptStats.getPoolName(), mptStats);

        PoolStatistics merkleStats = verifierPool.getMerklePoolStats();
        stats.put(merkleStats.getPoolName(), merkleStats);

        PoolStatistics signerStats = messageSignerPool.getPoolStats();
        stats.put(signerStats.getPoolName(), signerStats);

        return stats;
    }

    public void evictIdleResources() {
        log.info("Evicting idle resources from all pools...");
        rpcConnectionPool.evictIdleConnections();
        verifierPool.evictIdleVerifiers();
        messageSignerPool.evictIdleSigners();
        log.info("Idle resource eviction completed");
    }
}
