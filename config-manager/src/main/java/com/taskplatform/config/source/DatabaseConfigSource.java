package com.taskplatform.config.source;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskplatform.config.ConfigSource;
import com.taskplatform.persistence.entity.ConfigEntry;
import com.taskplatform.persistence.mapper.ConfigEntryMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseConfigSource implements ConfigSource {

    private final ConfigEntryMapper configEntryMapper;
    private final Cache<String, String> cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    @Override
    public String getName() {
        return "database";
    }

    @Override
    public String getValue(String namespace, String key) {
        String cacheKey = namespace + ":" + key;
        return cache.get(cacheKey, k -> loadFromDatabase(namespace, key));
    }

    private String loadFromDatabase(String namespace, String key) {
        try {
            ConfigEntry entry = configEntryMapper.selectOne(
                    new LambdaQueryWrapper<ConfigEntry>()
                            .eq(ConfigEntry::getNamespace, namespace)
                            .eq(ConfigEntry::getConfigKey, key)
                            .eq(ConfigEntry::getEnabled, true)
                            .orderByDesc(ConfigEntry::getVersion)
                            .last("LIMIT 1")
            );
            return entry != null ? entry.getConfigValue() : null;
        } catch (Exception e) {
            log.error("Failed to load config from database: {}:{}", namespace, key, e);
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int getPriority() {
        return 100;
    }

    public void invalidateCache(String namespace, String key) {
        cache.invalidate(namespace + ":" + key);
    }
}
