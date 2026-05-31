package com.datapipeline.data.cache;

import java.time.Duration;
import java.util.Optional;

public class NoOpCacheBackend implements CacheBackend {

    @Override
    public Optional<Object> get(String key) {
        return Optional.empty();
    }

    @Override
    public <T> void put(String key, T value, Duration ttl) {
    }

    @Override
    public void invalidate(String key) {
    }

    @Override
    public void invalidatePattern(String pattern) {
    }

    @Override
    public void invalidateAll() {
    }

}
