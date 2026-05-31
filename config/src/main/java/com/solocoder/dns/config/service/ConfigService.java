package com.solocoder.dns.config.service;

import com.solocoder.dns.common.entity.ConfigDefinition;
import com.solocoder.dns.common.exception.ResourceNotFoundException;
import com.solocoder.dns.config.validator.ConfigValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {
    private final ConfigValidator configValidator;
    private final Map<String, ConfigDefinition> configStore = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, ConfigDefinition>> versionStore = new ConcurrentHashMap<>();

    public ConfigDefinition createConfig(ConfigDefinition config) {
        configValidator.validate(config);
        config.setAppliedAt(LocalDateTime.now());
        configStore.put(config.getConfigId(), config);
        versionStore.computeIfAbsent(config.getConfigId(), k -> new ConcurrentHashMap<>())
                .put(config.getVersion(), config);
        log.info("Config created: {} v{}", config.getConfigId(), config.getVersion());
        return config;
    }

    public ConfigDefinition getConfig(String configId) {
        ConfigDefinition config = configStore.get(configId);
        if (config == null) {
            throw new ResourceNotFoundException("Config", configId);
        }
        return config;
    }

    public ConfigDefinition getConfig(String configId, Integer version) {
        Map<Integer, ConfigDefinition> versions = versionStore.get(configId);
        if (versions == null) {
            throw new ResourceNotFoundException("Config", configId);
        }
        ConfigDefinition config = versions.get(version);
        if (config == null) {
            throw new ResourceNotFoundException("Config version", configId + ":" + version);
        }
        return config;
    }

    public ConfigDefinition updateConfig(ConfigDefinition config) {
        configValidator.validate(config);
        ConfigDefinition existing = configStore.get(config.getConfigId());
        if (existing != null) {
            config.setVersion(existing.getVersion() + 1);
        } else {
            config.setVersion(1);
        }
        return createConfig(config);
    }

    public void deleteConfig(String configId) {
        configStore.remove(configId);
        versionStore.remove(configId);
        log.info("Config deleted: {}", configId);
    }

    public Map<String, Object> getMergedParameters(String namespace) {
        return configStore.values().stream()
                .filter(c -> namespace.equals(c.getNamespace()))
                .map(ConfigDefinition::getParameters)
                .flatMap(m -> m.entrySet().stream())
                .collect(ConcurrentHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), ConcurrentHashMap::putAll);
    }
}
