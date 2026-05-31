package com.solo.config.module.config.source;

import com.solo.config.module.config.ConfigSourceProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RedisConfigSource extends AbstractConfigSource {

    private final RedissonClient redissonClient;

    public RedisConfigSource(RedissonClient redissonClient, ConfigSourceProperties properties) {
        super(properties);
        this.redissonClient = redissonClient;
    }
    private static final String CONFIG_NAMESPACE_PREFIX = "config:";

    @Override
    public String getType() {
        return "redis";
    }

    @Override
    public int getPriority() {
        return 2;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getConfig(String namespace, String key) {
        try {
            RMap<String, String> configMap = redissonClient.getMap(CONFIG_NAMESPACE_PREFIX + namespace);
            return configMap.get(key);
        } catch (Exception e) {
            log.error("Failed to get config from redis, namespace: {}, key: {}", namespace, key, e);
            return null;
        }
    }

    @Override
    public void setConfig(String namespace, String key, String value) {
        try {
            RMap<String, String> configMap = redissonClient.getMap(CONFIG_NAMESPACE_PREFIX + namespace);
            configMap.put(key, value);
            log.info("Config saved to redis, namespace: {}, key: {}", namespace, key);
        } catch (Exception e) {
            log.error("Failed to set config to redis, namespace: {}, key: {}", namespace, key, e);
        }
    }

    @Override
    public void refresh() {
        log.debug("Redis config source refreshed");
    }
}
