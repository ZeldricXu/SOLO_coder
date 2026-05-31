package com.monitoring.config.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.monitoring.common.exception.MonitoringException;
import com.monitoring.common.model.ConfigDefinition;
import com.monitoring.config.validator.ConfigValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final ConfigValidator configValidator;

    private final Map<String, ConfigDefinition> configStore = new ConcurrentHashMap<>();

    private final Cache<String, ConfigDefinition> configCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(1000)
            .build();

    public ConfigDefinition createConfig(String namespace, Map<String, Object> parameters, ConfigValidator.ConfigSchema schema) {
        configValidator.validateOrThrow(parameters, schema);

        applyDefaults(parameters, schema);

        ConfigDefinition config = ConfigDefinition.builder()
                .configId("cfg_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .namespace(namespace)
                .version(1)
                .parameters(parameters)
                .enabled(true)
                .appliedAt(Instant.now())
                .build();

        configStore.put(config.getConfigId(), config);
        configCache.put(config.getConfigId(), config);

        log.info("Created config: id={}, namespace={}", config.getConfigId(), namespace);
        return config;
    }

    public ConfigDefinition updateConfig(String configId, Map<String, Object> parameters, ConfigValidator.ConfigSchema schema) {
        ConfigDefinition existing = configStore.get(configId);
        if (existing == null) {
            throw new MonitoringException(404, "Config not found: " + configId);
        }

        configValidator.validateOrThrow(parameters, schema);
        applyDefaults(parameters, schema);

        ConfigDefinition updated = ConfigDefinition.builder()
                .configId(configId)
                .namespace(existing.getNamespace())
                .version(existing.getVersion() + 1)
                .parameters(parameters)
                .enabled(existing.getEnabled())
                .appliedAt(Instant.now())
                .build();

        configStore.put(configId, updated);
        configCache.put(configId, updated);

        log.info("Updated config: id={}, version={}", configId, updated.getVersion());
        return updated;
    }

    public ConfigDefinition getConfig(String configId) {
        return configCache.get(configId, key -> configStore.get(key));
    }

    public Map<String, Object> getParameters(String configId) {
        ConfigDefinition config = getConfig(configId);
        return config != null ? config.getParameters() : new HashMap<>();
    }

    public <T> T getParameter(String configId, String key, Class<T> type) {
        Map<String, Object> parameters = getParameters(configId);
        Object value = parameters.get(key);
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        throw new MonitoringException(400, "Invalid type for parameter: " + key);
    }

    public <T> T getParameterOrDefault(String configId, String key, T defaultValue) {
        T value = (T) getParameters(configId).get(key);
        return value != null ? value : defaultValue;
    }

    public void deleteConfig(String configId) {
        configStore.remove(configId);
        configCache.invalidate(configId);
        log.info("Deleted config: id={}", configId);
    }

    private void applyDefaults(Map<String, Object> parameters, ConfigValidator.ConfigSchema schema) {
        for (ConfigValidator.ConfigField field : schema.getFields()) {
            if (!parameters.containsKey(field.getName()) && field.getDefaultValue() != null) {
                parameters.put(field.getName(), field.getDefaultValue());
            }
        }
    }

    public void toggleConfig(String configId, boolean enabled) {
        ConfigDefinition config = configStore.get(configId);
        if (config == null) {
            throw new MonitoringException(404, "Config not found: " + configId);
        }
        config.setEnabled(enabled);
        configCache.put(configId, config);
    }
}
