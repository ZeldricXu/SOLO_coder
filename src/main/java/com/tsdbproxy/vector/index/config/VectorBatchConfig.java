package com.tsdbproxy.vector.index.config;

import com.tsdbproxy.vector.index.impl.batch.CacheVectorBatchStore;
import com.tsdbproxy.vector.index.spi.NearestNeighborIndex;
import com.tsdbproxy.vector.index.spi.VectorBatchStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class VectorBatchConfig {

    @Value("${vector.cache.max-size:1000}")
    private int cacheMaxSize;

    @Value("${vector.cache.ttl-minutes:60}")
    private int ttlMinutes;

    @Bean
    public Map<Long, NearestNeighborIndex> vectorIndexCache() {
        return new ConcurrentHashMap<>();
    }

    @Bean
    public VectorBatchStore vectorBatchStore(
            ReactiveRedisTemplate<String, Object> redisTemplate) {
        return new CacheVectorBatchStore(
                cacheMaxSize,
                Duration.ofMinutes(ttlMinutes),
                redisTemplate
        );
    }
}
