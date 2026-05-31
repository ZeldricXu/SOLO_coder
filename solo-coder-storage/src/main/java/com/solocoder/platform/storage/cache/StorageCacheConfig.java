package com.solocoder.platform.storage.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
public class StorageCacheConfig {

    @Value("${storage.cache.max-size:10000}")
    private long maxSize;

    @Value("${storage.cache.expire-after-write:600}")
    private long expireSeconds;

    @Value("${storage.cache.hot-key-threshold:5}")
    private int hotKeyThreshold;

    @Bean
    public StorageCacheManager storageCacheManager() {
        log.info("Creating StorageCacheManager: maxSize={}, expire={}s, hotKeyThreshold={}", maxSize, expireSeconds, hotKeyThreshold);
        return new StorageCacheManager(maxSize, Duration.ofSeconds(expireSeconds), hotKeyThreshold);
    }
}
