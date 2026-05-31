package com.logmanager.infrastructure.config;

import com.logmanager.domain.model.LogEntry;
import com.logmanager.service.cache.Cache;
import com.logmanager.service.cache.CaffeineCache;
import com.logmanager.service.cache.MultiLevelCache;
import com.logmanager.service.cache.RedisCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import java.time.Duration;

@Configuration
public class CacheConfiguration {

    @Value("${cache.log-entry.l1.max-size:10000}")
    private long logEntryL1MaxSize;

    @Value("${cache.log-entry.l1.ttl:5m}")
    private Duration logEntryL1Ttl;

    @Value("${cache.log-entry.l2.ttl:30m}")
    private Duration logEntryL2Ttl;

    @Bean
    public Cache<String, LogEntry> logEntryCache(ReactiveStringRedisTemplate redisTemplate,
                                                 MeterRegistry meterRegistry) {
        Cache<String, LogEntry> l1Cache = new CaffeineCache<>("log-entry-l1", logEntryL1Ttl, logEntryL1MaxSize);
        Cache<String, LogEntry> l2Cache = new RedisCache<>("log-entry-l2", redisTemplate, LogEntry.class, logEntryL2Ttl);
        return new MultiLevelCache<>("log-entry", l1Cache, l2Cache, null, meterRegistry);
    }
}
