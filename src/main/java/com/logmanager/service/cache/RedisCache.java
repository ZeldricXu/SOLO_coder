package com.logmanager.service.cache;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;
import java.time.Duration;

@Slf4j
@RequiredArgsConstructor
public class RedisCache<K, V> implements Cache<K, V> {

    private final String name;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final Class<V> valueType;
    private final Duration defaultTtl;

    public RedisCache(String name, ReactiveStringRedisTemplate redisTemplate, Class<V> valueType, Duration defaultTtl) {
        this.name = name;
        this.redisTemplate = redisTemplate;
        this.valueType = valueType;
        this.defaultTtl = defaultTtl;
        log.info("Redis L2 cache '{}' initialized with defaultTtl={}", name, defaultTtl);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Mono<V> get(K key) {
        return redisTemplate.opsForValue()
                .get(buildKey(key))
                .map(this::deserialize)
                .doOnError(e -> log.warn("Failed to get from Redis cache '{}': {}", name, e.getMessage()));
    }

    @Override
    public Mono<Void> put(K key, V value) {
        return put(key, value, defaultTtl);
    }

    @Override
    public Mono<Void> put(K key, V value, Duration ttl) {
        String json = serialize(value);
        return redisTemplate.opsForValue()
                .set(buildKey(key), json, ttl)
                .doOnError(e -> log.warn("Failed to put to Redis cache '{}': {}", name, e.getMessage()))
                .then();
    }

    @Override
    public Mono<Void> invalidate(K key) {
        return redisTemplate.delete(buildKey(key))
                .doOnError(e -> log.warn("Failed to invalidate from Redis cache '{}': {}", name, e.getMessage()))
                .then();
    }

    @Override
    public Mono<Void> invalidateAll() {
        return redisTemplate.delete(redisTemplate.keys(name + ":*")
                        .collectList()
                        .flatMapMany(keys -> reactor.core.publisher.Flux.fromIterable(keys)))
                .collectList()
                .then()
                .doOnError(e -> log.warn("Failed to invalidateAll from Redis cache '{}': {}", name, e.getMessage()));
    }

    @Override
    public Mono<Boolean> contains(K key) {
        return redisTemplate.hasKey(buildKey(key))
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Long> size() {
        return redisTemplate.keys(name + ":*")
                .count()
                .defaultIfEmpty(0L);
    }

    private String buildKey(K key) {
        return name + ":" + key.toString();
    }

    private String serialize(V value) {
        return JSON.toJSONString(value);
    }

    private V deserialize(String json) {
        return JSON.parseObject(json, valueType);
    }
}
