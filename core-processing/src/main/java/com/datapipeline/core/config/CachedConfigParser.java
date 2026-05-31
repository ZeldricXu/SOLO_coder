package com.datapipeline.core.config;

import com.datapipeline.common.model.ConfigDefinition;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class CachedConfigParser {

    private final ConfigParser delegate;
    private final Cache<String, ProcessConfig> configCache;
    private final AtomicReference<ProcessConfig> defaultConfigRef = new AtomicReference<>();

    public CachedConfigParser() {
        this(new ConfigParser(), 1000, 1, TimeUnit.MINUTES);
    }

    public CachedConfigParser(ConfigParser delegate, int maxSize, long ttl, TimeUnit ttlUnit) {
        this.delegate = delegate;
        this.configCache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl, ttlUnit)
                .build();
    }

    public ProcessConfig parse(ConfigDefinition config) {
        if (config == null) {
            return getDefaultConfig();
        }
        String cacheKey = config.getConfigId() + "_v" + config.getVersion();
        return configCache.get(cacheKey, key -> delegate.parse(config));
    }

    public ProcessConfig getDefaultConfig() {
        ProcessConfig config = defaultConfigRef.get();
        if (config == null) {
            config = ProcessConfig.defaults();
            defaultConfigRef.set(config);
        }
        return config;
    }

    public void invalidate(String configId, int version) {
        configCache.invalidate(configId + "_v" + version);
    }

    public void invalidateAll() {
        configCache.invalidateAll();
    }

}
