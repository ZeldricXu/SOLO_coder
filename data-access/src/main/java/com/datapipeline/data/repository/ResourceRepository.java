package com.datapipeline.data.repository;

import com.datapipeline.common.model.Entity;
import com.datapipeline.data.cache.CacheManager;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class ResourceRepository {

    private final Map<String, Entity> store = new ConcurrentHashMap<>();
    private final CacheManager cacheManager;

    public ResourceRepository(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public Entity save(Entity entity) {
        if (entity.getId() == null) {
            throw new IllegalArgumentException("Entity ID cannot be null");
        }
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(Instant.now());
        }
        entity.setUpdatedAt(Instant.now());
        store.put(entity.getId(), entity);
        cacheManager.invalidate(getCacheKey(entity.getId()));
        cacheManager.invalidatePattern("resource:list:*");
        log.info("Resource saved: id={}, type={}", entity.getId(), entity.getType());
        return entity;
    }

    public Optional<Entity> findById(String id) {
        String cacheKey = getCacheKey(id);
        Optional<Entity> cached = cacheManager.get(cacheKey);
        if (cached.isPresent()) {
            return cached;
        }
        Entity entity = store.get(id);
        if (entity != null) {
            cacheManager.put(cacheKey, entity, Duration.ofMinutes(10));
            return Optional.of(entity);
        }
        return Optional.empty();
    }

    public List<Entity> findByType(String type) {
        String cacheKey = "resource:list:type:" + type;
        Optional<List<Entity>> cached = cacheManager.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }
        List<Entity> results = store.values().stream()
                .filter(e -> type.equals(e.getType()))
                .collect(Collectors.toList());
        cacheManager.put(cacheKey, results, Duration.ofMinutes(5));
        return results;
    }

    public List<Entity> findByStatus(String status) {
        return store.values().stream()
                .filter(e -> status.equals(e.getStatus()))
                .collect(Collectors.toList());
    }

    public List<Entity> findAll() {
        return new ArrayList<>(store.values());
    }

    public void deleteById(String id) {
        store.remove(id);
        cacheManager.invalidate(getCacheKey(id));
        cacheManager.invalidatePattern("resource:list:*");
        log.info("Resource deleted: id={}", id);
    }

    public boolean existsById(String id) {
        return findById(id).isPresent();
    }

    public long count() {
        return store.size();
    }

    private String getCacheKey(String id) {
        return "resource:" + id;
    }

}
