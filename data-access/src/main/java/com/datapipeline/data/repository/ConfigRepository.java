package com.datapipeline.data.repository;

import com.datapipeline.common.model.ConfigDefinition;
import com.datapipeline.data.cache.CacheManager;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class ConfigRepository {

    private final Map<String, ConfigDefinition> store = new ConcurrentHashMap<>();
    private final CacheManager cacheManager;

    public ConfigRepository(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public ConfigDefinition save(ConfigDefinition config) {
        if (config.getConfigId() == null) {
            throw new IllegalArgumentException("Config ID cannot be null");
        }
        if (config.getAppliedAt() == null) {
            config.setAppliedAt(Instant.now());
        }
        store.put(config.getConfigId(), config);
        cacheManager.invalidate(getCacheKey(config.getConfigId()));
        cacheManager.invalidate("config:namespace:" + config.getNamespace());
        log.info("Config saved: id={}, namespace={}, version={}",
                config.getConfigId(), config.getNamespace(), config.getVersion());
        return config;
    }

    public Optional<ConfigDefinition> findById(String configId) {
        String cacheKey = getCacheKey(configId);
        Optional<ConfigDefinition> cached = cacheManager.get(cacheKey);
        if (cached.isPresent()) {
            return cached;
        }
        ConfigDefinition config = store.get(configId);
        if (config != null) {
            cacheManager.put(cacheKey, config, Duration.ofMinutes(30));
            return Optional.of(config);
        }
        return Optional.empty();
    }

    public Optional<ConfigDefinition> findLatestByNamespace(String namespace) {
        String cacheKey = "config:namespace:" + namespace;
        Optional<ConfigDefinition> cached = cacheManager.get(cacheKey);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<ConfigDefinition> latest = store.values().stream()
                .filter(c -> namespace.equals(c.getNamespace()) && c.isEnabled())
                .max(Comparator.comparingInt(ConfigDefinition::getVersion));
        latest.ifPresent(c -> cacheManager.put(cacheKey, c, Duration.ofMinutes(15)));
        return latest;
    }

    public List<ConfigDefinition> findAllByNamespace(String namespace) {
        return store.values().stream()
                .filter(c -> namespace.equals(c.getNamespace()))
                .sorted(Comparator.comparingInt(ConfigDefinition::getVersion).reversed())
                .collect(Collectors.toList());
    }

    public List<ConfigDefinition> findAll() {
        return new ArrayList<>(store.values());
    }

    public void deleteById(String configId) {
        ConfigDefinition config = store.get(configId);
        if (config != null) {
            store.remove(configId);
            cacheManager.invalidate(getCacheKey(configId));
            cacheManager.invalidate("config:namespace:" + config.getNamespace());
            log.info("Config deleted: id={}", configId);
        }
    }

    public boolean existsById(String configId) {
        return findById(configId).isPresent();
    }

    private String getCacheKey(String configId) {
        return "config:" + configId;
    }

}
