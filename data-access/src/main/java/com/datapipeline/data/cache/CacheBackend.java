package com.datapipeline.data.cache;

import java.time.Duration;
import java.util.Optional;

public interface CacheBackend {

    Optional<Object> get(String key);

    <T> void put(String key, T value, Duration ttl);

    void invalidate(String key);

    void invalidatePattern(String pattern);

    void invalidateAll();

}
