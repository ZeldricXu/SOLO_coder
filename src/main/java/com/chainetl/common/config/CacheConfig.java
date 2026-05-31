package com.chainetl.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    @Primary
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .initialCapacity(100)
                .maximumSize(1000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .expireAfterAccess(3, TimeUnit.MINUTES)
                .recordStats());
        cacheManager.setCacheNames(java.util.List.of(
                "blocks",
                "transactions",
                "gasPrices",
                "nonces",
                "storageRecords",
                "multisigProposals",
                "multisigSignatures",
                "zkpProofs",
                "eventListeners",
                "indexerBlocks",
                "indexerTxns"
        ));
        return cacheManager;
    }

    @Bean
    public RedisCacheManager redisCacheManager(ReactiveRedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultCacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultCacheConfig)
                .withInitialCacheConfigurations(java.util.Map.of(
                        "blocks:l2", defaultCacheConfig.entryTtl(Duration.ofMinutes(30)),
                        "transactions:l2", defaultCacheConfig.entryTtl(Duration.ofMinutes(30)),
                        "gasPrices:l2", defaultCacheConfig.entryTtl(Duration.ofMinutes(5)),
                        "multisig:proposals:l2", defaultCacheConfig.entryTtl(Duration.ofMinutes(30)),
                        "multisig:signatures:l2", defaultCacheConfig.entryTtl(Duration.ofMinutes(30)),
                        "multisig:list:l2", defaultCacheConfig.entryTtl(Duration.ofMinutes(10)),
                        "indexer:blocks:l2", defaultCacheConfig.entryTtl(Duration.ofMinutes(15)),
                        "indexer:txns:l2", defaultCacheConfig.entryTtl(Duration.ofMinutes(15))
                ))
                .build();
    }

    @Bean
    public ReactiveRedisTemplate<String, Object> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();

        RedisSerializationContext<String, Object> context = RedisSerializationContext
                .<String, Object>newSerializationContext(keySerializer)
                .value(valueSerializer)
                .hashKey(keySerializer)
                .hashValue(valueSerializer)
                .build();

        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }

    @Bean
    public com.github.benmanes.caffeine.cache.Cache<String, Object> caffeineCache() {
        return Caffeine.newBuilder()
                .initialCapacity(200)
                .maximumSize(2000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }
}
