package com.tsdbproxy.vector.index.impl.batch;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tsdbproxy.vector.index.model.VectorDocument;
import com.tsdbproxy.vector.index.spi.VectorBatchStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CacheVectorBatchStore implements VectorBatchStore {

    private final AsyncCache<Long, List<VectorDocument>> l1Cache;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final Duration ttl;

    public CacheVectorBatchStore(int maxSize, Duration ttl,
                                 ReactiveRedisTemplate<String, Object> redisTemplate) {
        this.ttl = ttl;
        this.redisTemplate = redisTemplate;
        this.l1Cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
                .buildAsync();
    }

    @Override
    public Mono<Void> batchSave(Map<Long, List<VectorDocument>> documentsByIndex) {
        return Flux.fromIterable(documentsByIndex.entrySet())
                .flatMap(entry -> {
                    Long indexId = entry.getKey();
                    List<VectorDocument> docs = entry.getValue();

                    l1Cache.put(indexId, CompletableFuture.completedFuture(docs));

                    String redisKey = "vector:" + indexId;
                    return redisTemplate.opsForValue().set(redisKey, docs, ttl)
                            .doOnError(e -> log.warn("L2批量写入失败: indexId={}", indexId, e))
                            .onErrorResume(e -> Mono.empty());
                })
                .then();
    }

    @Override
    public Mono<Map<Long, List<VectorDocument>>> batchLoad(List<Long> indexIds) {
        Map<Long, List<VectorDocument>> result = new HashMap<>();
        List<Long> needFromL2 = new java.util.ArrayList<>();

        for (Long indexId : indexIds) {
            CompletableFuture<List<VectorDocument>> future = l1Cache.getIfPresent(indexId);
            if (future != null) {
                try {
                    List<VectorDocument> docs = future.get();
                    if (docs != null) {
                        result.put(indexId, docs);
                        continue;
                    }
                } catch (Exception e) {
                    log.warn("L1批量读取失败: indexId={}", indexId, e);
                }
            }
            needFromL2.add(indexId);
        }

        if (needFromL2.isEmpty()) {
            return Mono.just(result);
        }

        return Flux.fromIterable(needFromL2)
                .flatMap(indexId -> {
                    String redisKey = "vector:" + indexId;
                    return redisTemplate.opsForValue().get(redisKey)
                            .map(obj -> {
                                @SuppressWarnings("unchecked")
                                List<VectorDocument> docs = (List<VectorDocument>) obj;
                                l1Cache.put(indexId, CompletableFuture.completedFuture(docs));
                                result.put(indexId, docs);
                                return indexId;
                            })
                            .doOnError(e -> log.warn("L2批量读取失败: indexId={}", indexId, e))
                            .onErrorResume(e -> Mono.empty());
                })
                .then(Mono.just(result));
    }

    @Override
    public Mono<Void> batchDelete(List<Long> indexIds) {
        return Flux.fromIterable(indexIds)
                .flatMap(indexId -> {
                    l1Cache.synchronous().invalidate(indexId);
                    String redisKey = "vector:" + indexId;
                    return redisTemplate.delete(redisKey)
                            .doOnError(e -> log.warn("L2批量删除失败: indexId={}", indexId, e))
                            .onErrorResume(e -> Mono.empty());
                })
                .then();
    }
}
