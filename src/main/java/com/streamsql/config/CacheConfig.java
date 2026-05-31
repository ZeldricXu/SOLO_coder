package com.streamsql.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Value("${streamsql.cache.caffeine.maximum-size:10000}")
    private long maximumSize;

    @Value("${streamsql.cache.caffeine.expire-after-write:3600s}")
    private String expireAfterWrite;

    @Value("${streamsql.cache.caffeine.expire-after-access:1800s}")
    private String expireAfterAccess;

    @Bean
    @Primary
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(Duration.parse(expireAfterWrite.replace("s", "").equals(expireAfterWrite) ? 
                    expireAfterWrite : "PT" + expireAfterWrite.toUpperCase().replace("S", "S")))
                .expireAfterAccess(Duration.parse(expireAfterAccess.replace("s", "").equals(expireAfterAccess) ?
                    expireAfterAccess : "PT" + expireAfterAccess.toUpperCase().replace("S", "S")))
                .recordStats());
        return cacheManager;
    }

    @Bean
    public CacheManager redisCacheManager(ReactiveRedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));
        
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
