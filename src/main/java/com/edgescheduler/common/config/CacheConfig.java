package com.edgescheduler.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Value("${edge.scheduler.cache.caffeine.maximum-size:10000}")
    private int maximumSize;

    @Value("${edge.scheduler.cache.caffeine.expire-after-write:3600s}")
    private Duration expireAfterWrite;

    @Bean
    @Primary
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(expireAfterWrite)
                .recordStats());
        return cacheManager;
    }
}
