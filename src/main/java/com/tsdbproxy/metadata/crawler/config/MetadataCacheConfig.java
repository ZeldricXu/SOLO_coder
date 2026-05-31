package com.tsdbproxy.metadata.crawler.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsdbproxy.metadata.crawler.impl.cache.MultiLevelCrawlResultCache;
import com.tsdbproxy.metadata.crawler.spi.CrawlResultCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import java.time.Duration;

@Configuration
public class MetadataCacheConfig {

    @Value("${metadata.cache.l1.max-size:1000}")
    private int l1MaxSize;

    @Value("${metadata.cache.ttl-minutes:60}")
    private int ttlMinutes;

    @Bean
    public CrawlResultCache crawlResultCache(
            ReactiveRedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        return new MultiLevelCrawlResultCache(
                redisTemplate,
                objectMapper,
                meterRegistry,
                l1MaxSize,
                Duration.ofMinutes(ttlMinutes)
        );
    }
}
