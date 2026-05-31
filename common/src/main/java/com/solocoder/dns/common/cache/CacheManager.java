package com.solocoder.dns.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class CacheManager {
    private final Cache<String, Object> localCache;

    public CacheManager() {
        this.localCache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .expireAfterAccess(2, TimeUnit.MINUTES)
                .build();
    }

    public void put(String key, Object value) {
        localCache.put(key, value);
    }

    public Object get(String key) {
        return localCache.getIfPresent(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        Object value = localCache.getIfPresent(key);
        if (value != null && clazz.isInstance(value)) {
            return (T) value;
        }
        return null;
    }

    public void invalidate(String key) {
        localCache.invalidate(key);
    }

    public void invalidateAll() {
        localCache.invalidateAll();
    }
}
