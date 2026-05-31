package com.chain.infrastructure.txbuilder.cache;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;

@Slf4j
@RequiredArgsConstructor
public class DefaultMultilevelCache<K, V> implements MultilevelCache<K, V> {

    private final String name;
    private final Cache<K, V> caffeineCache;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Class<V> valueType;
    private final Duration redisTtl;
    private final boolean enableRedis;

    @Override
    public Mono<V> get(K key) {
        return Mono.justOrEmpty(caffeineCache.getIfPresent(key))
                .doOnNext(v -> log.debug("L1 cache hit: key={}", key))
                .switchIfEmpty(
                        enableRedis ? getFromRedis(key) : Mono.empty()
                )
                .doOnNext(v -> caffeineCache.put(key, v))
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("Cache miss: key={}", key);
                    return Mono.empty();
                }));
    }

    private Mono<V> getFromRedis(K key) {
        String redisKey = buildRedisKey(key);
        return redisTemplate.opsForValue().get(redisKey)
                .doOnNext(v -> log.debug("L2 cache hit: key={}", key))
                .map(this::deserialize)
                .onErrorResume(e -> {
                    log.warn("Redis get failed: key={}, error={}", key, e.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<V> put(K key, V value) {
        return Mono.fromRunnable(() -> caffeineCache.put(key, value))
                .then(
                        enableRedis ? putToRedis(key, value) : Mono.empty()
                )
                .thenReturn(value)
                .doOnNext(v -> log.debug("Cache put: key={}", key));
    }

    private Mono<Void> putToRedis(K key, V value) {
        String redisKey = buildRedisKey(key);
        return redisTemplate.opsForValue().set(redisKey, serialize(value), redisTtl)
                .onErrorResume(e -> {
                    log.warn("Redis set failed: key={}, error={}", key, e.getMessage());
                    return Mono.just(false);
                })
                .then();
    }

    @Override
    public Mono<Void> evict(K key) {
        return Mono.fromRunnable(() -> caffeineCache.invalidate(key))
                .then(
                        enableRedis ? redisTemplate.delete(buildRedisKey(key)).onErrorResume(e -> Mono.empty()).then()
                                : Mono.empty()
                )
                .doOnSuccess(v -> log.debug("Cache evict: key={}", key));
    }

    @Override
    public Mono<Void> evictAll() {
        return Mono.fromRunnable(() -> caffeineCache.invalidateAll())
                .doOnSuccess(v -> log.debug("Cache evict all: name={}", name));
    }

    @Override
    public String getName() {
        return name;
    }

    private String buildRedisKey(K key) {
        return "txbuilder:" + name + ":" + key;
    }

    private String serialize(V value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize cache value", e);
        }
    }

    private V deserialize(String json) {
        try {
            return objectMapper.readValue(json, valueType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize cache value", e);
        }
    }
}
