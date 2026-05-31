package com.tsdbproxy.vector.index.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.tsdbproxy.vector.index.model.VectorDocument;
import com.tsdbproxy.vector.index.spi.VectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheVectorStore implements VectorStore {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final Cache<String, Object> caffeineCache;

    private final java.util.Map<Long, List<VectorDocument>> memoryStore = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void save(Long indexId, List<VectorDocument> documents) {
        memoryStore.put(indexId, new CopyOnWriteArrayList<>(documents));
        log.info("向量存储保存: indexId={}, 向量数量={}", indexId, documents.size());
    }

    @Override
    public List<VectorDocument> load(Long indexId) {
        List<VectorDocument> docs = memoryStore.get(indexId);
        if (docs != null) {
            log.info("从内存加载向量: indexId={}, 数量={}", indexId, docs.size());
        }
        return docs;
    }
}
