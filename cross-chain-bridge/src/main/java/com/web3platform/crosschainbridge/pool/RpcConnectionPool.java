package com.web3platform.crosschainbridge.pool;

import com.web3platform.crosschainbridge.config.ResourcePoolConfig;
import com.web3platform.crosschainbridge.model.PoolStatistics;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

@Slf4j
@Component
public class RpcConnectionPool {

    private final ResourcePoolConfig config;
    private final Map<String, GenericObjectPool<PooledRpcConnection>> pools = new ConcurrentHashMap<>();
    private final Map<String, Long> totalCreatedCounters = new ConcurrentHashMap<>();

    public RpcConnectionPool(ResourcePoolConfig config) {
        this.config = config;
    }

    public void initialize() {
        Map<String, String> chainRpcUrls = config.getChainRpc();
        if (chainRpcUrls == null || chainRpcUrls.isEmpty()) {
            log.warn("No chain RPC URLs configured for connection pool");
            return;
        }
        for (Map.Entry<String, String> entry : chainRpcUrls.entrySet()) {
            String chainId = entry.getKey();
            GenericObjectPoolConfig<PooledRpcConnection> poolConfig = new GenericObjectPoolConfig<>();
            poolConfig.setMaxTotal(config.getRpcPoolMaxTotal());
            poolConfig.setMaxIdle(config.getRpcPoolMaxPerRoute());
            poolConfig.setMinIdle(1);
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestOnReturn(false);
            poolConfig.setEvictionPolicyClassName(
                    "org.apache.commons.pool2.impl.DefaultEvictionPolicy");

            PooledRpcConnectionFactory factory = new PooledRpcConnectionFactory(chainId, entry.getValue(), config);
            GenericObjectPool<PooledRpcConnection> pool = new GenericObjectPool<>(factory, poolConfig);
            pools.put(chainId, pool);
            totalCreatedCounters.put(chainId, 0L);
            log.info("Initialized RPC connection pool for chain: {}", chainId);
        }
    }

    public PooledRpcConnection borrowConnection(String chainId) {
        GenericObjectPool<PooledRpcConnection> pool = pools.get(chainId);
        if (pool == null) {
            throw new IllegalArgumentException("No pool configured for chain: " + chainId);
        }
        try {
            PooledRpcConnection connection = pool.borrowObject();
            connection.markInUse();
            log.debug("Borrowed RPC connection for chain: {}, active={}, idle={}",
                    chainId, pool.getNumActive(), pool.getNumIdle());
            return connection;
        } catch (Exception e) {
            log.error("Failed to borrow RPC connection for chain: {}", chainId, e);
            throw new RuntimeException("Failed to borrow RPC connection", e);
        }
    }

    public void returnConnection(PooledRpcConnection connection) {
        if (connection == null) {
            return;
        }
        String chainId = connection.getChainId();
        GenericObjectPool<PooledRpcConnection> pool = pools.get(chainId);
        if (pool == null) {
            log.warn("No pool found for chain when returning connection: {}", chainId);
            return;
        }
        connection.markReturned();
        pool.returnObject(connection);
        log.debug("Returned RPC connection for chain: {}", chainId);
    }

    public void invalidateConnection(PooledRpcConnection connection) {
        if (connection == null) {
            return;
        }
        String chainId = connection.getChainId();
        GenericObjectPool<PooledRpcConnection> pool = pools.get(chainId);
        if (pool == null) {
            log.warn("No pool found for chain when invalidating connection: {}", chainId);
            return;
        }
        try {
            pool.invalidateObject(connection);
            log.info("Invalidated RPC connection for chain: {}", chainId);
        } catch (Exception e) {
            log.error("Failed to invalidate RPC connection for chain: {}", chainId, e);
        }
    }

    public PoolStatistics getPoolStats(String chainId) {
        GenericObjectPool<PooledRpcConnection> pool = pools.get(chainId);
        if (pool == null) {
            return null;
        }
        return PoolStatistics.builder()
                .poolName("RPC-" + chainId)
                .activeCount(pool.getNumActive())
                .idleCount(pool.getNumIdle())
                .waitCount(pool.getNumWaiters())
                .totalCreated(totalCreatedCounters.getOrDefault(chainId, 0L))
                .build();
    }

    public PoolStatistics getPoolStats() {
        int totalActive = 0;
        int totalIdle = 0;
        int totalWait = 0;
        long totalCreated = 0;

        for (Map.Entry<String, GenericObjectPool<PooledRpcConnection>> entry : pools.entrySet()) {
            GenericObjectPool<PooledRpcConnection> pool = entry.getValue();
            totalActive += pool.getNumActive();
            totalIdle += pool.getNumIdle();
            totalWait += pool.getNumWaiters();
            totalCreated += totalCreatedCounters.getOrDefault(entry.getKey(), 0L);
        }

        return PoolStatistics.builder()
                .poolName("RPC-ALL")
                .activeCount(totalActive)
                .idleCount(totalIdle)
                .waitCount(totalWait)
                .totalCreated(totalCreated)
                .build();
    }

    public void shutdown() {
        for (Map.Entry<String, GenericObjectPool<PooledRpcConnection>> entry : pools.entrySet()) {
            entry.getValue().close();
            log.info("Closed RPC connection pool for chain: {}", entry.getKey());
        }
        pools.clear();
    }

    public void evictIdleConnections() {
        for (Map.Entry<String, GenericObjectPool<PooledRpcConnection>> entry : pools.entrySet()) {
            try {
                entry.getValue().evict();
            } catch (Exception e) {
                log.warn("Error evicting idle connections for chain: {}", entry.getKey(), e);
            }
        }
    }

    public class PooledRpcConnectionFactory extends BasePooledObjectFactory<PooledRpcConnection> {

        private final String chainId;
        private final String rpcUrl;
        private final ResourcePoolConfig config;

        public PooledRpcConnectionFactory(String chainId, String rpcUrl, ResourcePoolConfig config) {
            this.chainId = chainId;
            this.rpcUrl = rpcUrl;
            this.config = config;
        }

        @Override
        public PooledRpcConnection create() throws Exception {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(config.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS)
                    .readTimeout(config.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                    .build();
            HttpService httpService = new HttpService(rpcUrl, client);
            Web3j web3j = Web3j.build(httpService);
            totalCreatedCounters.merge(chainId, 1L, Long::sum);
            log.debug("Created new RPC connection for chain: {}, totalCreated={}",
                    chainId, totalCreatedCounters.getOrDefault(chainId, 0L));
            return new PooledRpcConnection(web3j, chainId);
        }

        @Override
        public PooledObject<PooledRpcConnection> wrap(PooledRpcConnection connection) {
            return new DefaultPooledObject<>(connection);
        }

        @Override
        public void destroyObject(PooledObject<PooledRpcConnection> p) throws Exception {
            PooledRpcConnection connection = p.getObject();
            if (connection.getWeb3j() != null) {
                connection.getWeb3j().shutdown();
            }
            log.debug("Destroyed RPC connection for chain: {}", chainId);
        }

        @Override
        public boolean validateObject(PooledObject<PooledRpcConnection> p) {
            PooledRpcConnection connection = p.getObject();
            try {
                if (connection.getWeb3j() == null) {
                    return false;
                }
                connection.getWeb3j().ethBlockNumber().send();
                return true;
            } catch (Exception e) {
                log.warn("RPC connection validation failed for chain: {}", chainId);
                return false;
            }
        }

        @Override
        public void passivateObject(PooledObject<PooledRpcConnection> p) {
            p.getObject().markReturned();
        }

        @Override
        public void activateObject(PooledObject<PooledRpcConnection> p) {
            p.getObject().markInUse();
        }
    }
}
