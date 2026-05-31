package com.tsdbproxy.metadata.crawler.impl.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsdbproxy.metadata.crawler.spi.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@RequiredArgsConstructor
public class L2DistributedCache<K, V> implements Cache<K, V> {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final String cacheName;
    private final Class<V> valueType;
    private final Duration defaultTtl;

    @Override
    public Mono<V> get(K key) {
        String redisKey = buildKey(key);
        return redisTemplate.opsForValue().get(redisKey)
                .map(obj -> objectMapper.convertValue(obj, valueType))
                .doOnNext(v -> log.debug("L2缓存命中: key={}", redisKey))
                .doOnError(e -> log.warn("L2缓存读取失败: key={}", redisKey, e))
                .onErrorResume(e -> Mono.empty());
    }

    @Override
    public Mono<Void> put(K key, V value, Duration ttl) {
        String redisKey = buildKey(key);
        return redisTemplate.opsForValue().set(redisKey, value, ttl)
                .doOnSuccess(v -> log.debug("L2缓存写入: key={}, ttl={}s", redisKey, ttl.getSeconds()))
                .doOnError(e -> log.warn("L2缓存写入失败: key={}", redisKey, e))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    @Override
    public Mono<Void> invalidate(K key) {
        String redisKey = buildKey(key);
        return redisTemplate.delete(redisKey)
                .doOnSuccess(v -> log.debug("L2缓存失效: key={}", redisKey))
                .doOnError(e -> log.warn("L2缓存失效失败: key={}", redisKey, e))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    @Override
    public Mono<Void> invalidateAll() {
        String pattern = cacheName + ":*";
        return redisTemplate.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(pattern).build())
                .flatMap(key -> redisTemplate.delete(key))
                .collectList()
                .doOnSuccess(keys -> log.info("L2缓存全部失效, 删除key数量={}", keys.size()))
                .then();
    }

    @Override
    public String getCacheName() {
        return cacheName + "_L2";
    }

    private String buildKey(K key) {
        return cacheName + ":" + key.toString();
    }
}
