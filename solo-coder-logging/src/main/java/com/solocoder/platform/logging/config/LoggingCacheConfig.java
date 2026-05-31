package com.solocoder.platform.logging.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.solocoder.platform.logging.cache.*;
import com.solocoder.platform.logging.model.LogLevelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Configuration
@EnableScheduling
public class LoggingCacheConfig {

    @Value("${logging.cache.l1.max-size:10000}")
    private long l1MaxSize;

    @Value("${logging.cache.l1.expire-after-write:300}")
    private long l1ExpireSeconds;

    @Value("${logging.cache.l2.default-ttl:3600}")
    private long l2DefaultTtlSeconds;

    @Value("${logging.cache.invalidation.interval:60}")
    private long invalidationIntervalSeconds;

    @Value("${logging.cache.warmup-on-start:true}")
    private boolean warmupOnStart;

    @Bean
    public String nodeId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Bean
    public L1LocalCache l1LocalCache(String nodeId) {
        return new L1LocalCache(nodeId, l1MaxSize, Duration.ofSeconds(l1ExpireSeconds));
    }

    @Bean
    @ConditionalOnProperty(name = "spring.data.redis.enabled", havingValue = "true", matchIfMissing = true)
    public L2DistributedCache l2DistributedCache(org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        return new L2DistributedCache(redisTemplate, Duration.ofSeconds(l2DefaultTtlSeconds));
    }

    @Bean
    public MultiLevelCacheManager multiLevelCacheManager(L1LocalCache l1Cache,
                                                         @org.springframework.beans.factory.annotation.Autowired(required = false) L2DistributedCache l2Cache) {
        L2DistributedCache effectiveL2 = l2Cache != null ? l2Cache : new NullL2DistributedCache();
        return new MultiLevelCacheManager(l1Cache, effectiveL2);
    }

    @Bean
    public CacheWarmupStrategy cacheWarmupStrategy(MultiLevelCacheManager cacheManager) {
        List<LogLevelConfig> seedConfigs = new ArrayList<>();
        seedConfigs.add(LogLevelConfig.builder()
                .loggerName("com.solocoder")
                .level("INFO")
                .scope("DEFAULT")
                .ttlSeconds(0)
                .build());
        return new CacheWarmupStrategy(cacheManager, seedConfigs);
    }

    @Bean
    public CacheInvalidationStrategy cacheInvalidationStrategy(MultiLevelCacheManager cacheManager) {
        return new CacheInvalidationStrategy(cacheManager, invalidationIntervalSeconds);
    }

    @Bean
    public RedisMessageListenerContainer redisContainer(RedisConnectionFactory connectionFactory,
                                                        MessageListenerAdapter listenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, new ChannelTopic("log:level:invalidate"));
        return container;
    }

    @Bean
    public MessageListenerAdapter listenerAdapter(com.solocoder.platform.logging.listener.RedisInvalidationListener listener) {
        return new MessageListenerAdapter(listener);
    }

    private static class NullL2DistributedCache extends L2DistributedCache {
        NullL2DistributedCache() {
            super(null, Duration.ZERO);
        }

        @Override
        public java.util.Optional<LogLevelConfig> get(String loggerName) {
            return java.util.Optional.empty();
        }

        @Override
        public void put(String loggerName, LogLevelConfig config) {
        }

        @Override
        public void invalidate(String loggerName) {
        }

        @Override
        public void invalidateAll() {
        }

        @Override
        public java.util.Map<String, LogLevelConfig> getAll() {
            return java.util.Map.of();
        }

        @Override
        public void publishInvalidation(String loggerName) {
        }
    }
}
