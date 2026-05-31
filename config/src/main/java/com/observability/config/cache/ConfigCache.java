package com.observability.config.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class ConfigCache {

    private final Cache<String, Map<String, Object>> cache;

    public ConfigCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();
    }

    public Optional<Map<String, Object>> get(String namespace) {
        return Optional.ofNullable(cache.getIfPresent(namespace));
    }

    public void put(String namespace, Map<String, Object> config) {
        cache.put(namespace, config);
    }

    public void invalidate(String namespace) {
        cache.invalidate(namespace);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}
