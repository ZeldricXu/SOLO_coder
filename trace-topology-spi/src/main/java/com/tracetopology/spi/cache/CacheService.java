package com.tracetopology.spi.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

public interface CacheService {

    <T> void put(String key, T value, Duration ttl);

    <T> Optional<T> get(String key, Class<T> type);

    <T> T getOrLoad(String key, Class<T> type, Supplier<T> loader, Duration ttl);

    void invalidate(String key);

    void invalidateAll(String pattern);

    boolean exists(String key);

    long increment(String key, long delta);

    long decrement(String key, long delta);
}
