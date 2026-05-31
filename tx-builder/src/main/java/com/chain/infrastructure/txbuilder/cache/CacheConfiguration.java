package com.chain.infrastructure.txbuilder.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chain.infrastructure.persistence.entity.ChainTransaction;
import java.time.Duration;
import com.github.benmanes.caffeine.cache.Cache;

@Configuration
@EnableCaching
public class CacheConfiguration {

    @Bean
    public CacheProperties transactionCacheProperties() {
        return new CacheProperties(
                "transactions",
                Duration.ofMinutes(30),
                10000,
                true,
                Duration.ofHours(2)
        );
    }

    @Bean
    public MultilevelCache<String, ChainTransaction> transactionCache(
            CacheProperties properties,
            ReactiveStringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {

        Cache<String, ChainTransaction> caffeineCache = Caffeine.newBuilder()
                .expireAfterWrite(properties.getTtl())
                .maximumSize(properties.getMaxSize())
                .build();

        return new DefaultMultilevelCache<>(
                properties.getName(),
                caffeineCache,
                redisTemplate,
                objectMapper,
                ChainTransaction.class,
                properties.getRedisTtl(),
                properties.isEnableRedis()
        );
    }
}
