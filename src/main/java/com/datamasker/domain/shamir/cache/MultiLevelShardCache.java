package com.datamasker.domain.shamir.cache;

import com.datamasker.domain.shamir.model.KeyShard;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MultiLevelShardCache {

    private final Cache<String, ShardCacheEntry> l1Cache;
    private final Map<String, ShardCacheEntry> l2Cache;
    private final AtomicLong hitCount;
    private final AtomicLong missCount;
    private volatile long warmupTimeMs;

    public MultiLevelShardCache(
            @Value("${datamasker.shamir.cache.l1.max-size:1000}") int l1MaxSize,
            @Value("${datamasker.shamir.cache.l1.expire-after-access-min:5}") int l1ExpireAfterAccessMin) {
        this.l1Cache = Caffeine.newBuilder()
                .maximumSize(l1MaxSize)
                .expireAfterAccess(l1ExpireAfterAccessMin, TimeUnit.MINUTES)
                .build();
        this.l2Cache = new ConcurrentHashMap<>();
        this.hitCount = new AtomicLong(0);
        this.missCount = new AtomicLong(0);
        this.warmupTimeMs = 0;
    }

    public ShardCacheEntry get(String secretId, int shardIndex) {
        String key = buildKey(secretId, shardIndex);
        ShardCacheEntry entry = l1Cache.getIfPresent(key);
        if (entry != null) {
            hitCount.incrementAndGet();
            updateAccessStats(entry);
            return entry;
        }
        entry = l2Cache.get(key);
        if (entry != null) {
            hitCount.incrementAndGet();
            l1Cache.put(key, entry);
            updateAccessStats(entry);
            return entry;
        }
        missCount.incrementAndGet();
        return null;
    }

    public void put(String secretId, int shardIndex, ShardCacheEntry entry) {
        String key = buildKey(secretId, shardIndex);
        entry.setCreatedAt(System.currentTimeMillis());
        entry.setLastAccessed(System.currentTimeMillis());
        entry.setAccessCount(0);
        l1Cache.put(key, entry);
        l2Cache.put(key, entry);
    }

    public void invalidate(String secretId, int shardIndex) {
        String key = buildKey(secretId, shardIndex);
        l1Cache.invalidate(key);
        l2Cache.remove(key);
    }

    public void invalidateBySecretId(String secretId) {
        String prefix = secretId + "_";
        l1Cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        l2Cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void invalidateAll() {
        l1Cache.invalidateAll();
        l2Cache.clear();
        hitCount.set(0);
        missCount.set(0);
        warmupTimeMs = 0;
    }

    public void preheat(List<KeyShard> shards) {
        long startTime = System.currentTimeMillis();
        for (KeyShard shard : shards) {
            ShardCacheEntry entry = new ShardCacheEntry();
            entry.setShardData(shard.getShardData());
            entry.setThreshold(shard.getThreshold());
            entry.setOwner(shard.getOwner());
            put(shard.getSecretId(), shard.getShardIndex(), entry);
        }
        warmupTimeMs = System.currentTimeMillis() - startTime;
    }

    public CacheStats getStats() {
        CacheStats stats = new CacheStats();
        long hits = hitCount.get();
        long misses = missCount.get();
        long total = hits + misses;
        stats.setHitCount(hits);
        stats.setMissCount(misses);
        stats.setHitRate(total > 0 ? (double) hits / total : 0.0);
        stats.setL1Size((int) l1Cache.estimatedSize());
        stats.setL2Size(l2Cache.size());
        stats.setWarmupTimeMs(warmupTimeMs);
        return stats;
    }

    private String buildKey(String secretId, int shardIndex) {
        return secretId + "_" + shardIndex;
    }

    private void updateAccessStats(ShardCacheEntry entry) {
        entry.setAccessCount(entry.getAccessCount() + 1);
        entry.setLastAccessed(System.currentTimeMillis());
    }
}
