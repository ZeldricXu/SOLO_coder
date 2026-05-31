package com.solo.config.module.config.source;

import com.solo.config.module.config.ConfigSourceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class LocalConfigSource extends AbstractConfigSource {

    private final Environment environment;
    private final ConcurrentHashMap<String, String> localCache = new ConcurrentHashMap<>();

    public LocalConfigSource(Environment environment, ConfigSourceProperties properties) {
        super(properties);
        this.environment = environment;
    }

    @Override
    public String getType() {
        return "local";
    }

    @Override
    public int getPriority() {
        return 3;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getConfig(String namespace, String key) {
        String cacheKey = namespace + ":" + key;
        String value = localCache.get(cacheKey);
        if (value != null) {
            return value;
        }
        String envKey = namespace + "." + key;
        value = environment.getProperty(envKey);
        if (value != null) {
            localCache.put(cacheKey, value);
        }
        return value;
    }

    @Override
    public void setConfig(String namespace, String key, String value) {
        String cacheKey = namespace + ":" + key;
        localCache.put(cacheKey, value);
        log.info("Config saved to local, namespace: {}, key: {}", namespace, key);
    }

    @Override
    public void refresh() {
        localCache.clear();
        log.info("Local config source refreshed, cache cleared");
    }
}
