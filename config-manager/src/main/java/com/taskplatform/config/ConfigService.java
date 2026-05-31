package com.taskplatform.config;

import com.taskplatform.common.exception.BusinessException;
import com.taskplatform.common.util.IdGenerator;
import com.taskplatform.common.util.JsonUtil;
import com.taskplatform.config.source.DatabaseConfigSource;
import com.taskplatform.persistence.entity.ConfigEntry;
import com.taskplatform.persistence.mapper.ConfigEntryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final List<ConfigSource> configSources;
    private final ConfigEntryMapper configEntryMapper;
    private final DatabaseConfigSource databaseConfigSource;

    public String getString(String namespace, String key) {
        return getString(namespace, key, null);
    }

    public String getString(String namespace, String key, String defaultValue) {
        List<ConfigSource> sortedSources = new ArrayList<>(configSources);
        sortedSources.sort(Comparator.comparingInt(ConfigSource::getPriority));

        for (ConfigSource source : sortedSources) {
            if (!source.isAvailable()) continue;
            String value = source.getValue(namespace, key);
            if (value != null) {
                log.debug("Config loaded from {}: {}.{} = {}", source.getName(), namespace, key, value);
                return value;
            }
        }
        return defaultValue;
    }

    public int getInt(String namespace, String key, int defaultValue) {
        String value = getString(namespace, key);
        try {
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long getLong(String namespace, String key, long defaultValue) {
        String value = getString(namespace, key);
        try {
            return value != null ? Long.parseLong(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String namespace, String key, boolean defaultValue) {
        String value = getString(namespace, key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    public double getDouble(String namespace, String key, double defaultValue) {
        String value = getString(namespace, key);
        try {
            return value != null ? Double.parseDouble(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public <T> T getObject(String namespace, String key, Class<T> clazz) {
        String value = getString(namespace, key);
        return value != null ? JsonUtil.fromJson(value, clazz) : null;
    }

    public ConfigEntry setConfig(String namespace, String key, String value, String appliedBy) {
        Integer currentVersion = configEntryMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ConfigEntry>()
                        .eq(ConfigEntry::getNamespace, namespace)
                        .eq(ConfigEntry::getConfigKey, key)
        ).intValue();

        ConfigEntry entry = new ConfigEntry();
        entry.setConfigId(IdGenerator.generateConfigId());
        entry.setNamespace(namespace);
        entry.setConfigKey(key);
        entry.setConfigValue(value);
        entry.setVersion(currentVersion + 1);
        entry.setEnabled(true);
        entry.setAppliedAt(LocalDateTime.now());
        entry.setAppliedBy(appliedBy);
        entry.setSource("api");

        configEntryMapper.insert(entry);
        databaseConfigSource.invalidateCache(namespace, key);

        log.info("Config updated: {}.{} = {}", namespace, key, value);
        return entry;
    }

    public ConfigEntry setConfigFromMap(String namespace, String key, Map<String, Object> value, String appliedBy) {
        return setConfig(namespace, key, JsonUtil.toJson(value), appliedBy);
    }
}
